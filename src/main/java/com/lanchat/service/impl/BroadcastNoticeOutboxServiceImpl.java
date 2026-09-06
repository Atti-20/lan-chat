package com.lanchat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.cluster.RealtimeRouter;
import com.lanchat.dto.WebSocketEnvelope;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastNoticeOutboxTask;
import com.lanchat.entity.BroadcastReceiver;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.User;
import com.lanchat.mapper.BroadcastMapper;
import com.lanchat.mapper.BroadcastNoticeOutboxTaskMapper;
import com.lanchat.mapper.BroadcastReceiverMapper;
import com.lanchat.service.BroadcastNotificationAccountService;
import com.lanchat.service.BroadcastNoticeOutboxService;
import com.lanchat.service.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists a broadcast card and its post-commit routing intent atomically.
 *
 * <p>Realtime transport is intentionally outside the database transaction.
 * The outbox emits only a broadcast refresh + conversation sync request, never
 * card content.  A target removal atomically revokes refresh tasks, redacts
 * the card, and writes durable recall tasks, so an old transport retry cannot
 * expose the removed card body.</p>
 */
@Service
public class BroadcastNoticeOutboxServiceImpl implements BroadcastNoticeOutboxService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastNoticeOutboxServiceImpl.class);
    private static final String TASK_SYNC = "SYNC";
    private static final String TASK_RECALL = "RECALL";
    private static final String NOTICE_OVERVIEW = "OVERVIEW";
    private static final String NOTICE_REMINDER = "REMINDER";
    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String DISPATCHED = "DISPATCHED";
    private static final String REVOKED = "REVOKED";

    private final BroadcastNoticeOutboxTaskMapper taskMapper;
    private final BroadcastMapper broadcastMapper;
    private final BroadcastReceiverMapper receiverMapper;
    private final BroadcastNotificationAccountService notificationAccountService;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;
    private final RealtimeRouter realtimeRouter;
    private final TransactionTemplate requiresNewTransaction;

    public BroadcastNoticeOutboxServiceImpl(BroadcastNoticeOutboxTaskMapper taskMapper,
                                            BroadcastMapper broadcastMapper,
                                            BroadcastReceiverMapper receiverMapper,
                                            BroadcastNotificationAccountService notificationAccountService,
                                            ChatMessageService chatMessageService,
                                            ObjectMapper objectMapper,
                                            RealtimeRouter realtimeRouter,
                                            PlatformTransactionManager transactionManager) {
        this.taskMapper = taskMapper;
        this.broadcastMapper = broadcastMapper;
        this.receiverMapper = receiverMapper;
        this.notificationAccountService = notificationAccountService;
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
        this.realtimeRouter = realtimeRouter;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public void queueOverview(Broadcast broadcast, BroadcastReceiver receiver, User sender) {
        queueCard(broadcast, receiver, sender, false, null);
    }

    @Override
    @Transactional
    public void queueReminder(Broadcast broadcast,
                              BroadcastReceiver receiver,
                              User sender,
                              Long operatorId) {
        queueCard(broadcast, receiver, sender, true, operatorId);
    }

    @Override
    @Transactional
    public void revokeRecipient(Broadcast broadcast, BroadcastReceiver receiver) {
        if (broadcast == null || broadcast.getId() == null || receiver == null
                || receiver.getUserId() == null) return;
        int generation = noticeGeneration(receiver);
        taskMapper.revokePendingRecipientDispatches(
                broadcast.getId(), receiver.getUserId(), generation, "RECIPIENT_REMOVED");

        // Do not swallow a failure here.  The receiver removal and every card
        // redaction must commit together, otherwise a removed user could keep
        // reading a sensitive card indefinitely after a node restart.
        User account = notificationAccountService.ensureAccount();
        List<ChatMessage> redacted = chatMessageService.redactSystemBroadcastCards(
                account.getId(), receiver.getUserId(), broadcast.getId());
        for (ChatMessage message : redacted) {
            if (message == null || message.getMessageId() == null
                    || message.getConversationId() == null) continue;
            BroadcastNoticeOutboxTask task = new BroadcastNoticeOutboxTask();
            task.setIdempotencyKey("broadcast-recall-" + message.getMessageId());
            task.setTaskType(TASK_RECALL);
            task.setBroadcastId(broadcast.getId());
            task.setReceiverUserId(receiver.getUserId());
            task.setNoticeGeneration(generation);
            task.setMessageId(message.getMessageId());
            task.setConversationId(message.getConversationId());
            enqueueAndSchedule(task);
        }
    }

    @Override
    @Transactional
    public void revokeBroadcastDispatches(Long broadcastId) {
        if (broadcastId == null) return;
        taskMapper.revokePendingBroadcastDispatches(broadcastId, "BROADCAST_INACTIVE");
    }

    @Override
    @Transactional
    public void revokePendingReminderDispatches(Long broadcastId,
                                                Long receiverUserId,
                                                Integer noticeGeneration) {
        if (broadcastId == null || receiverUserId == null) return;
        taskMapper.revokePendingReminderDispatches(
                broadcastId, receiverUserId,
                Math.max(1, noticeGeneration == null ? 1 : noticeGeneration),
                "RECIPIENT_ALREADY_SUBMITTED");
    }

    @Override
    public int retryPending() {
        List<Long> ids = taskMapper.selectDueTaskIds(100);
        if (ids == null || ids.isEmpty()) return 0;
        int dispatched = 0;
        for (Long taskId : ids) {
            if (dispatchTask(taskId)) dispatched++;
        }
        return dispatched;
    }

    @Override
    public boolean dispatchTask(Long taskId) {
        DispatchClaim claim;
        try {
            claim = claimTask(taskId);
        } catch (RuntimeException exception) {
            // A post-commit attempt must never turn an already committed
            // broadcast mutation into a controller failure. The durable task
            // remains PENDING (or its lease eventually expires) for the
            // scheduler to retry.
            log.warn("广播通知任务领取失败，将由扫描器重试: taskId={}, error={}",
                    taskId, exception.getMessage());
            return false;
        }
        if (claim == null) return false;
        try {
            dispatch(claim);
            return true;
        } catch (RuntimeException exception) {
            try {
                retryTask(claim.taskId(), claim.leaseToken(), exception);
            } catch (RuntimeException retryException) {
                // Keep the claimed lease intact if even the retry write fails;
                // it becomes eligible again when the lease expires.
                log.warn("广播通知任务重试状态保存失败: taskId={}, error={}",
                        claim.taskId(), retryException.getMessage());
            }
            log.warn("广播通知投递失败，将重试: taskId={}, error={}",
                    taskId, exception.getMessage());
            return false;
        }
    }

    private void queueCard(Broadcast broadcast,
                           BroadcastReceiver receiver,
                           User sender,
                           boolean reminder,
                           Long operatorId) {
        if (!canPersistCard(broadcast, receiver, reminder)) return;
        int generation = noticeGeneration(receiver);
        int reminderSequence = reminder ? reminderSequence(receiver) : 0;
        if (reminder && reminderSequence < 1) return;

        User account = notificationAccountService.ensureAccount();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("kind", reminder ? "BROADCAST_REMINDER" : "BROADCAST_OVERVIEW");
        card.put("broadcastId", broadcast.getId());
        card.put("noticeGeneration", generation);
        card.put("title", broadcast.getTitle());
        card.put("summary", concise(broadcast.getContent()));
        card.put("priority", broadcast.getPriority());
        card.put("deadlineAt", broadcast.getDeadlineAt());
        card.put("confirmationRequired", broadcast.getConfirmationRequired());
        card.put("sender", sender == null || sender.getNickname() == null ? "" : sender.getNickname());
        if (reminder) {
            card.put("reminder", reminderMessage(broadcast, operatorId));
            card.put("operatorId", operatorId);
            card.put("reminderSequence", reminderSequence);
        }

        String clientMsgId = cardClientMessageId(
                broadcast.getId(), receiver.getUserId(), generation, reminder, reminderSequence);
        try {
            ChatMessage message = chatMessageService.saveSystemDirectMessage(
                    account.getId(), receiver.getUserId(), "broadcast",
                    objectMapper.writeValueAsString(card), clientMsgId);
            BroadcastNoticeOutboxTask task = new BroadcastNoticeOutboxTask();
            task.setIdempotencyKey("broadcast-sync-" + clientMsgId);
            task.setTaskType(TASK_SYNC);
            task.setNoticeKind(reminder ? NOTICE_REMINDER : NOTICE_OVERVIEW);
            task.setBroadcastId(broadcast.getId());
            task.setReceiverUserId(receiver.getUserId());
            task.setNoticeGeneration(generation);
            task.setMessageId(message.getMessageId());
            task.setConversationId(message.getConversationId());
            enqueueAndSchedule(task);
        } catch (Exception exception) {
            throw new IllegalStateException("广播通知卡持久化失败", exception);
        }
    }

    /** A committed row means a schedule failure can be recovered by the scanner. */
    private void enqueueAndSchedule(BroadcastNoticeOutboxTask task) {
        if (task == null || task.getIdempotencyKey() == null) return;
        taskMapper.enqueue(task);
        if (task.getId() == null) {
            throw new IllegalStateException("广播通知投递任务未返回标识");
        }
        Long taskId = task.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchAfterCommit(taskId);
                }
            });
        } else {
            dispatchAfterCommit(taskId);
        }
    }

    private void dispatchAfterCommit(Long taskId) {
        try {
            dispatchTask(taskId);
        } catch (RuntimeException exception) {
            // Do not propagate from TransactionSynchronization.afterCommit:
            // the source transaction is already durable and the scanner owns
            // recovery of this task.
            log.warn("广播通知提交后调度失败，将由扫描器重试: taskId={}, error={}",
                    taskId, exception.getMessage());
        }
    }

    /**
     * Global lock order is broadcast row then outbox row.  Source mutations
     * use the same order, while transport happens only after this transaction
     * commits and releases both locks.
     */
    private DispatchClaim claimTask(Long taskId) {
        if (taskId == null) return null;
        return requiresNewTransaction.execute(status -> {
            BroadcastNoticeOutboxTask snapshot = taskMapper.selectById(taskId);
            if (snapshot == null || !claimable(snapshot)) return null;

            Broadcast lockedBroadcast = null;
            if (TASK_SYNC.equals(snapshot.getTaskType()) && snapshot.getBroadcastId() != null) {
                lockedBroadcast = broadcastMapper.selectByIdForUpdate(snapshot.getBroadcastId());
            }
            BroadcastNoticeOutboxTask task = taskMapper.selectByIdForUpdate(taskId);
            if (task == null || !claimable(task)) return null;
            if (TASK_SYNC.equals(task.getTaskType())) {
                BroadcastReceiver receiver = task.getBroadcastId() == null || task.getReceiverUserId() == null
                        ? null : receiverMapper.selectReceiver(task.getBroadcastId(), task.getReceiverUserId());
                if (!canDispatchSync(lockedBroadcast, receiver, task)) {
                    revoke(task, "SOURCE_STATE_CHANGED");
                    return null;
                }
            } else if (!TASK_RECALL.equals(task.getTaskType())
                    || task.getReceiverUserId() == null || task.getMessageId() == null
                    || task.getConversationId() == null) {
                revoke(task, "INVALID_OUTBOX_TASK");
                return null;
            }

            String leaseToken = UUID.randomUUID().toString().replace("-", "");
            task.setStatus(PROCESSING);
            task.setLeaseToken(leaseToken);
            task.setLeaseUntil(LocalDateTime.now().plusSeconds(30));
            task.setAttempts(Math.max(0, task.getAttempts() == null ? 0 : task.getAttempts()) + 1);
            task.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(task);
            return new DispatchClaim(task.getId(), leaseToken, task.getTaskType(),
                    task.getBroadcastId(), task.getReceiverUserId(), task.getMessageId(),
                    task.getConversationId());
        });
    }

    private void dispatch(DispatchClaim claim) {
        if (TASK_SYNC.equals(claim.taskType())) {
            // Only identifiers cross the realtime boundary.  The client refreshes
            // visibility and conversation history against the committed state.
            realtimeRouter.sendToUser(claim.receiverUserId(), envelope(
                    "BROADCAST", null, Map.of("broadcastId", claim.broadcastId())));
            boolean routed = realtimeRouter.sendToUserWithReceipt(claim.receiverUserId(), envelope(
                    "SYNC_REQUIRED", null, Map.of("reason", "BROADCAST_NOTICE")),
                    () -> completeTask(claim.taskId(), claim.leaseToken()));
            if (!routed) {
                throw new IllegalStateException("广播通知实时路由未被接受");
            }
            completeTask(claim.taskId(), claim.leaseToken());
            return;
        }
        boolean routed = realtimeRouter.sendToUserWithReceipt(claim.receiverUserId(), envelope(
                "CHAT_RECALL", claim.conversationId(), Map.of("messageId", claim.messageId())),
                () -> completeTask(claim.taskId(), claim.leaseToken()));
        if (!routed) {
            throw new IllegalStateException("广播通知撤回实时路由未被接受");
        }
        completeTask(claim.taskId(), claim.leaseToken());
    }

    private void completeTask(Long taskId, String leaseToken) {
        requiresNewTransaction.executeWithoutResult(status -> {
            BroadcastNoticeOutboxTask task = taskMapper.selectByIdForUpdate(taskId);
            if (task == null || !PROCESSING.equals(task.getStatus())
                    || !leaseToken.equals(task.getLeaseToken())) return;
            task.setStatus(DISPATCHED);
            task.setLeaseToken(null);
            task.setLeaseUntil(null);
            task.setLastError(null);
            task.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(task);
        });
    }

    private void retryTask(Long taskId, String leaseToken, RuntimeException exception) {
        requiresNewTransaction.executeWithoutResult(status -> {
            BroadcastNoticeOutboxTask task = taskMapper.selectByIdForUpdate(taskId);
            if (task == null || !PROCESSING.equals(task.getStatus())
                    || !leaseToken.equals(task.getLeaseToken())) return;
            int attempts = Math.max(1, task.getAttempts() == null ? 1 : task.getAttempts());
            long delaySeconds = Math.min(3600L, 2L << Math.min(10, attempts - 1));
            task.setStatus(PENDING);
            task.setLeaseToken(null);
            task.setLeaseUntil(null);
            task.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds));
            task.setLastError(safeError(exception));
            task.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(task);
        });
    }

    private void revoke(BroadcastNoticeOutboxTask task, String reason) {
        task.setStatus(REVOKED);
        task.setLeaseToken(null);
        task.setLeaseUntil(null);
        task.setLastError(reason);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private boolean claimable(BroadcastNoticeOutboxTask task) {
        if (task == null) return false;
        if (PENDING.equals(task.getStatus())) {
            return task.getNextRetryAt() == null || !task.getNextRetryAt().isAfter(LocalDateTime.now());
        }
        return PROCESSING.equals(task.getStatus()) && task.getLeaseUntil() != null
                && !task.getLeaseUntil().isAfter(LocalDateTime.now());
    }

    private boolean canDispatchSync(Broadcast broadcast,
                                    BroadcastReceiver receiver,
                                    BroadcastNoticeOutboxTask task) {
        if (broadcast == null || receiver == null || task == null
                || !"ACTIVE".equals(receiver.getTargetStatus())
                || noticeGeneration(receiver) != Math.max(1,
                task.getNoticeGeneration() == null ? 1 : task.getNoticeGeneration())) return false;
        if (NOTICE_REMINDER.equals(task.getNoticeKind())) {
            return "ACTIVE".equals(broadcast.getStatus())
                    && receiver.getConfirmedAt() == null
                    && (broadcast.getDeadlineAt() == null
                    || broadcast.getDeadlineAt().isAfter(LocalDateTime.now()));
        }
        return NOTICE_OVERVIEW.equals(task.getNoticeKind())
                && ("ACTIVE".equals(broadcast.getStatus()) || "COMPLETED".equals(broadcast.getStatus()));
    }

    private boolean canPersistCard(Broadcast broadcast, BroadcastReceiver receiver, boolean reminder) {
        if (broadcast == null || broadcast.getId() == null || receiver == null
                || receiver.getUserId() == null || !"ACTIVE".equals(receiver.getTargetStatus())) return false;
        if (reminder) {
            return "ACTIVE".equals(broadcast.getStatus())
                    && receiver.getConfirmedAt() == null
                    && (broadcast.getDeadlineAt() == null
                    || broadcast.getDeadlineAt().isAfter(LocalDateTime.now()));
        }
        return "ACTIVE".equals(broadcast.getStatus()) || "COMPLETED".equals(broadcast.getStatus());
    }

    private WebSocketEnvelope envelope(String event, String conversationId, Map<String, Object> payload) {
        WebSocketEnvelope envelope = new WebSocketEnvelope();
        envelope.setEvent(event);
        envelope.setConversationId(conversationId);
        envelope.setTimestamp(System.currentTimeMillis());
        envelope.setPayload(new LinkedHashMap<>(payload));
        return envelope;
    }

    private int noticeGeneration(BroadcastReceiver receiver) {
        return receiver == null || receiver.getNoticeGeneration() == null
                ? 1 : Math.max(1, receiver.getNoticeGeneration());
    }

    private int reminderSequence(BroadcastReceiver receiver) {
        return receiver == null || receiver.getRemindCount() == null
                ? 0 : Math.max(0, receiver.getRemindCount());
    }

    private String concise(String content) {
        if (content == null) return "";
        return content.length() > 180 ? content.substring(0, 180) + "…" : content;
    }

    private String reminderMessage(Broadcast broadcast, Long operatorId) {
        if (operatorId == null) return "请尽快提交处理结果";
        boolean owner = broadcast != null && operatorId.equals(broadcast.getSenderId());
        return (owner ? "广播创建者" : "广播管理员") + "提醒你尽快提交处理结果";
    }

    private String cardClientMessageId(Long broadcastId,
                                      Long receiverUserId,
                                      int generation,
                                      boolean reminder,
                                      int reminderSequence) {
        String base = "broadcast-card-" + Long.toUnsignedString(broadcastId, 36)
                + "-" + Long.toUnsignedString(receiverUserId, 36) + "-g" + generation;
        return reminder ? "broadcast-reminder-" + Long.toUnsignedString(broadcastId, 36)
                + "-" + Long.toUnsignedString(receiverUserId, 36)
                + "-g" + generation + "-r" + reminderSequence : base;
    }

    private String safeError(RuntimeException exception) {
        String value = exception.getClass().getSimpleName() + ":" + exception.getMessage();
        value = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return value.substring(0, Math.min(240, value.length()));
    }

    private record DispatchClaim(Long taskId,
                                 String leaseToken,
                                 String taskType,
                                 Long broadcastId,
                                 Long receiverUserId,
                                 String messageId,
                                 String conversationId) {
    }
}
