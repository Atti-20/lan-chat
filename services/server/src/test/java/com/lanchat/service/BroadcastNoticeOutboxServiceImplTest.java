package com.lanchat.service;

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
import com.lanchat.service.impl.BroadcastNoticeOutboxServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BroadcastNoticeOutboxServiceImplTest {

    private static final long BROADCAST_ID = 31L;
    private static final long RECEIVER_ID = 9009L;

    private BroadcastNoticeOutboxTaskMapper taskMapper;
    private BroadcastMapper broadcastMapper;
    private BroadcastReceiverMapper receiverMapper;
    private BroadcastNotificationAccountService accountService;
    private ChatMessageService chatMessageService;
    private RealtimeRouter realtimeRouter;
    private BroadcastNoticeOutboxServiceImpl service;
    private final AtomicReference<BroadcastNoticeOutboxTask> storedTask = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        taskMapper = mock(BroadcastNoticeOutboxTaskMapper.class);
        broadcastMapper = mock(BroadcastMapper.class);
        receiverMapper = mock(BroadcastReceiverMapper.class);
        accountService = mock(BroadcastNotificationAccountService.class);
        chatMessageService = mock(ChatMessageService.class);
        realtimeRouter = mock(RealtimeRouter.class);
        when(realtimeRouter.sendToUserWithReceipt(any(), any(), any())).thenReturn(true);
        service = new BroadcastNoticeOutboxServiceImpl(
                taskMapper,
                broadcastMapper,
                receiverMapper,
                accountService,
                chatMessageService,
                new ObjectMapper(),
                realtimeRouter,
                new NoOpTransactionManager());
    }

    @Test
    void cardAndIntentCommitBeforeRealtimeRefreshAndTheRefreshCarriesNoCardBody() {
        Broadcast broadcast = activeBroadcast();
        BroadcastReceiver receiver = activeReceiver();
        User sender = activeUser(7L, "广播创建者");
        User technicalAccount = activeUser(101L, "广播通知");
        ChatMessage message = new ChatMessage();
        message.setMessageId("notice-card-31");
        message.setConversationId("private:101:9009");
        message.setClientMsgId("broadcast-card-v");

        when(accountService.ensureAccount()).thenReturn(technicalAccount);
        when(chatMessageService.saveSystemDirectMessage(
                eq(101L), eq(RECEIVER_ID), eq("broadcast"), any(), any())).thenReturn(message);
        configureStoredTask();
        when(broadcastMapper.selectByIdForUpdate(BROADCAST_ID)).thenReturn(broadcast);
        when(receiverMapper.selectReceiver(BROADCAST_ID, RECEIVER_ID)).thenReturn(receiver);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.queueOverview(broadcast, receiver, sender);

            verify(realtimeRouter, never()).sendToUser(any(), any());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            TransactionSynchronizationManager.clearSynchronization();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        ArgumentCaptor<WebSocketEnvelope> broadcastEvents = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(realtimeRouter).sendToUser(eq(RECEIVER_ID), broadcastEvents.capture());
        WebSocketEnvelope broadcastRefresh = broadcastEvents.getValue();
        assertEquals(BROADCAST_ID, broadcastRefresh.getPayload().get("broadcastId"));
        assertFalse(broadcastRefresh.getPayload().containsKey("content"));
        assertFalse(broadcastRefresh.getPayload().containsKey("receiver"));
        ArgumentCaptor<WebSocketEnvelope> syncEvents = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        ArgumentCaptor<Runnable> receiptCallback = ArgumentCaptor.forClass(Runnable.class);
        verify(realtimeRouter).sendToUserWithReceipt(
                eq(RECEIVER_ID), syncEvents.capture(), receiptCallback.capture());
        assertEquals("SYNC_REQUIRED", syncEvents.getValue().getEvent());
        // A successful local/cluster route is enough for this refresh task:
        // the card itself is durable and an offline client recovers on sync.
        assertEquals("DISPATCHED", storedTask.get().getStatus());
        receiptCallback.getValue().run();
        assertEquals("DISPATCHED", storedTask.get().getStatus());
        verify(realtimeRouter, never()).sendToUser(eq(RECEIVER_ID),
                org.mockito.ArgumentMatchers.argThat(event -> "CHAT_DELIVER".equals(event.getEvent())));
    }

    @Test
    void targetRemovalNeverSwallowsPersistentCardRedactionFailure() {
        Broadcast broadcast = activeBroadcast();
        BroadcastReceiver receiver = activeReceiver();
        receiver.setTargetStatus("REMOVED");
        when(accountService.ensureAccount()).thenReturn(activeUser(101L, "广播通知"));
        when(chatMessageService.redactSystemBroadcastCards(101L, RECEIVER_ID, BROADCAST_ID))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> service.revokeRecipient(broadcast, receiver));

        verify(taskMapper).revokePendingRecipientDispatches(
                BROADCAST_ID, RECEIVER_ID, 1, "RECIPIENT_REMOVED");
        verify(realtimeRouter, never()).sendToUser(any(), any());
    }

    @Test
    void committedRedactionUsesADurableRecallTaskInsteadOfLeakingCardContent() {
        Broadcast broadcast = activeBroadcast();
        BroadcastReceiver receiver = activeReceiver();
        receiver.setTargetStatus("REMOVED");
        User account = activeUser(101L, "广播通知");
        ChatMessage redacted = new ChatMessage();
        redacted.setMessageId("recalled-card-31");
        redacted.setConversationId("private:101:9009");
        when(accountService.ensureAccount()).thenReturn(account);
        when(chatMessageService.redactSystemBroadcastCards(101L, RECEIVER_ID, BROADCAST_ID))
                .thenReturn(List.of(redacted));
        configureStoredTask();

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.revokeRecipient(broadcast, receiver);
            verify(realtimeRouter, never()).sendToUser(any(), any());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        ArgumentCaptor<WebSocketEnvelope> event = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        ArgumentCaptor<Runnable> receiptCallback = ArgumentCaptor.forClass(Runnable.class);
        verify(realtimeRouter).sendToUserWithReceipt(
                eq(RECEIVER_ID), event.capture(), receiptCallback.capture());
        assertEquals("CHAT_RECALL", event.getValue().getEvent());
        assertEquals("recalled-card-31", event.getValue().getPayload().get("messageId"));
        assertFalse(event.getValue().getPayload().containsKey("content"));
        receiptCallback.getValue().run();
        assertEquals("DISPATCHED", storedTask.get().getStatus());
    }

    @Test
    void postCommitClaimFailureDoesNotEscapeTheCommittedSourceTransaction() {
        Broadcast broadcast = activeBroadcast();
        BroadcastReceiver receiver = activeReceiver();
        User technicalAccount = activeUser(101L, "广播通知");
        ChatMessage message = new ChatMessage();
        message.setMessageId("notice-card-31");
        message.setConversationId("private:101:9009");
        message.setClientMsgId("broadcast-card-v");
        when(accountService.ensureAccount()).thenReturn(technicalAccount);
        when(chatMessageService.saveSystemDirectMessage(
                eq(101L), eq(RECEIVER_ID), eq("broadcast"), any(), any())).thenReturn(message);
        configureStoredTask();
        when(taskMapper.selectById(73L)).thenThrow(new IllegalStateException("database unavailable"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.queueOverview(broadcast, receiver, activeUser(7L, "广播创建者"));
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            assertDoesNotThrow(() -> synchronizations.forEach(TransactionSynchronization::afterCommit));
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
        verify(realtimeRouter, never()).sendToUser(any(), any());

        org.mockito.Mockito.doReturn(storedTask.get()).when(taskMapper).selectById(73L);
        when(broadcastMapper.selectByIdForUpdate(BROADCAST_ID)).thenReturn(broadcast);
        when(receiverMapper.selectReceiver(BROADCAST_ID, RECEIVER_ID)).thenReturn(receiver);
        assertTrue(service.dispatchTask(73L));
        verify(realtimeRouter).sendToUserWithReceipt(eq(RECEIVER_ID), any(), any());
    }

    @Test
    void routeFailureReturnsTheClaimedTaskToPendingInsteadOfMarkingItDispatched() {
        Broadcast broadcast = activeBroadcast();
        BroadcastReceiver receiver = activeReceiver();
        BroadcastNoticeOutboxTask task = new BroadcastNoticeOutboxTask();
        task.setId(73L);
        task.setTaskType("SYNC");
        task.setNoticeKind("OVERVIEW");
        task.setBroadcastId(BROADCAST_ID);
        task.setReceiverUserId(RECEIVER_ID);
        task.setNoticeGeneration(1);
        task.setStatus("PENDING");
        task.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        storedTask.set(task);
        when(taskMapper.selectById(73L)).thenReturn(task);
        when(taskMapper.selectByIdForUpdate(73L)).thenReturn(task);
        when(taskMapper.updateById(any(BroadcastNoticeOutboxTask.class))).thenReturn(1);
        when(broadcastMapper.selectByIdForUpdate(BROADCAST_ID)).thenReturn(broadcast);
        when(receiverMapper.selectReceiver(BROADCAST_ID, RECEIVER_ID)).thenReturn(receiver);
        when(realtimeRouter.sendToUserWithReceipt(any(), any(), any())).thenReturn(false);

        assertFalse(service.dispatchTask(73L));

        assertEquals("PENDING", storedTask.get().getStatus());
        assertFalse(storedTask.get().getNextRetryAt().isBefore(LocalDateTime.now()));
    }

    private void configureStoredTask() {
        when(taskMapper.enqueue(any(BroadcastNoticeOutboxTask.class))).thenAnswer(invocation -> {
            BroadcastNoticeOutboxTask task = invocation.getArgument(0);
            task.setId(73L);
            task.setStatus("PENDING");
            task.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
            storedTask.set(task);
            return 1;
        });
        when(taskMapper.selectById(73L)).thenAnswer(invocation -> storedTask.get());
        when(taskMapper.selectByIdForUpdate(73L)).thenAnswer(invocation -> storedTask.get());
        when(taskMapper.updateById(any(BroadcastNoticeOutboxTask.class))).thenReturn(1);
    }

    private Broadcast activeBroadcast() {
        Broadcast broadcast = new Broadcast();
        broadcast.setId(BROADCAST_ID);
        broadcast.setSenderId(7L);
        broadcast.setStatus("ACTIVE");
        broadcast.setTitle("消防演练");
        broadcast.setContent("请在集合点签到");
        broadcast.setPriority("IMPORTANT");
        broadcast.setConfirmationRequired(true);
        return broadcast;
    }

    private BroadcastReceiver activeReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver();
        receiver.setBroadcastId(BROADCAST_ID);
        receiver.setUserId(RECEIVER_ID);
        receiver.setTargetStatus("ACTIVE");
        receiver.setConfirmStatus("PENDING");
        receiver.setNoticeGeneration(1);
        receiver.setRemindCount(0);
        return receiver;
    }

    private User activeUser(Long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        user.setStatus(1);
        return user;
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // The mocked mappers are the persistence boundary for this focused unit test.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
