package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.*;
import com.lanchat.entity.*;
import com.lanchat.mapper.*;
import com.lanchat.service.BroadcastService;
import com.lanchat.service.FileService;
import com.lanchat.service.FriendService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BroadcastServiceImpl implements BroadcastService {

    private static final List<String> PRIORITIES = List.of("NORMAL", "IMPORTANT", "EMERGENCY");
    private static final List<String> SCOPES = List.of("ALL", "USERS");
    private static final List<String> DEFAULT_CONFIRMATION_OPTIONS = List.of("EXECUTED");
    private static final int MAX_EXPLICIT_RECEIVERS = 500;
    private static final int MAX_CONFIRMATION_OPTIONS = 10;

    private final BroadcastMapper broadcastMapper;
    private final BroadcastReceiverMapper receiverMapper;
    private final UserMapper userMapper;
    private final FriendService friendService;
    private final ObjectMapper objectMapper;
    private final BroadcastEvidenceMapper evidenceMapper;
    private final FileMetadataMapper fileMetadataMapper;
    private final FileAccessGrantMapper fileAccessGrantMapper;
    private final FileService fileService;

    @Autowired
    public BroadcastServiceImpl(BroadcastMapper broadcastMapper,
                                BroadcastReceiverMapper receiverMapper,
                                UserMapper userMapper,
                                FriendService friendService,
                                ObjectMapper objectMapper,
                                BroadcastEvidenceMapper evidenceMapper,
                                FileMetadataMapper fileMetadataMapper,
                                FileAccessGrantMapper fileAccessGrantMapper,
                                FileService fileService) {
        this.broadcastMapper = broadcastMapper;
        this.receiverMapper = receiverMapper;
        this.userMapper = userMapper;
        this.friendService = friendService;
        this.objectMapper = objectMapper;
        this.evidenceMapper = evidenceMapper;
        this.fileMetadataMapper = fileMetadataMapper;
        this.fileAccessGrantMapper = fileAccessGrantMapper;
        this.fileService = fileService;
    }

    /** Compatibility constructor retained for focused unit tests of legacy behaviour. */
    public BroadcastServiceImpl(BroadcastMapper broadcastMapper,
                                BroadcastReceiverMapper receiverMapper,
                                UserMapper userMapper,
                                FriendService friendService,
                                ObjectMapper objectMapper) {
        this(broadcastMapper, receiverMapper, userMapper, friendService, objectMapper,
                null, null, null, null);
    }

    @Override
    @Transactional
    public Broadcast create(Long senderId, BroadcastCreateDTO request) {
        User sender = requireActiveUser(senderId);
        if (!isSystemAdmin(sender)
                && !Integer.valueOf(1).equals(sender.getCanSendBroadcast())) {
            throw new AccessDeniedException("当前账号没有广播发布权限");
        }
        if (request == null) throw new IllegalArgumentException("广播参数不能为空");

        String title = requiredText(request.getTitle(), "广播标题", 100);
        String content = requiredText(request.getContent(), "广播内容", 10000);
        String priority = normalizedEnum(request.getPriority(), "NORMAL", PRIORITIES, "广播优先级无效");
        String scope = normalizedEnum(request.getScopeType(), null, SCOPES, "广播范围无效");
        boolean confirmationRequired = Boolean.TRUE.equals(request.getConfirmationRequired());
        LocalDateTime now = LocalDateTime.now();
        if (request.getDeadlineAt() != null && !request.getDeadlineAt().isAfter(now)) {
            throw new IllegalArgumentException("广播截止时间必须晚于当前时间");
        }

        List<String> confirmationOptions = normalizeConfirmationOptions(
                confirmationRequired, request.getConfirmationOptions());
        Set<Long> receiverIds = resolveReceivers(sender, scope, request);
        if (receiverIds.isEmpty()) {
            throw new IllegalArgumentException("广播接收范围内没有需要接收的普通成员");
        }

        Broadcast broadcast = new Broadcast();
        broadcast.setSenderId(senderId);
        broadcast.setTitle(title);
        broadcast.setContent(content);
        broadcast.setPriority(priority);
        broadcast.setScopeType(scope);
        broadcast.setScopeGroupId(null);
        broadcast.setConfirmationRequired(confirmationRequired);
        broadcast.setConfirmationOptions(writeConfirmationOptions(confirmationOptions));
        broadcast.setDeadlineAt(request.getDeadlineAt());
        broadcast.setBypassMute(Boolean.TRUE.equals(request.getBypassMute()));
        broadcast.setRepeatReminder(Boolean.TRUE.equals(request.getRepeatReminder()));
        broadcast.setStatus("ACTIVE");
        broadcast.setCreateTime(now);
        broadcast.setUpdateTime(now);
        broadcast.setRequireImageProof(Boolean.TRUE.equals(request.getRequireImageProof()));
        broadcast.setRequireLocationProof(Boolean.TRUE.equals(request.getRequireLocationProof()));
        broadcast.setCompletedAt(null);
        if (broadcastMapper.insert(broadcast) != 1 || broadcast.getId() == null) {
            throw new IllegalStateException("广播创建失败");
        }

        if (evidenceMapper != null) {
            saveContentEvidence(broadcast, sender, request.getContentImageFileIds(),
                    request.getContentLocation(), now);
        }

        for (Long receiverId : receiverIds) {
            BroadcastReceiver receiver = new BroadcastReceiver();
            receiver.setBroadcastId(broadcast.getId());
            receiver.setUserId(receiverId);
            receiver.setConfirmStatus(confirmationRequired ? "PENDING" : "NOT_REQUIRED");
            receiver.setCreateTime(now);
            receiver.setUpdateTime(now);
            receiver.setTargetStatus("ACTIVE");
            receiver.setRemindCount(0);
            if (receiverMapper.insert(receiver) != 1) {
                throw new IllegalStateException("广播接收记录创建失败");
            }
            grantContentImagesToReceiver(request.getContentImageFileIds(), receiverId);
        }
        return broadcast;
    }

    @Override
    @Transactional
    public Broadcast cancel(Long broadcastId, Long operatorId) {
        User operator = requireActiveUser(operatorId);
        if (!isSystemAdmin(operator)) {
            throw new AccessDeniedException("只有管理员可以撤销广播");
        }
        Broadcast broadcast = requireBroadcastForUpdate(broadcastId);
        if ("CANCELLED".equals(broadcast.getStatus())) return broadcast;
        if (!"ACTIVE".equals(broadcast.getStatus())) {
            throw new IllegalArgumentException("当前广播状态无法撤销");
        }

        broadcast.setStatus("CANCELLED");
        broadcast.setUpdateTime(LocalDateTime.now());
        if (broadcastMapper.updateById(broadcast) != 1) {
            throw new IllegalStateException("广播撤销失败");
        }
        return broadcast;
    }

    @Override
    @Transactional
    public BroadcastDeleteResult delete(Long broadcastId, Long operatorId) {
        User operator = requireActiveUser(operatorId);
        if (!isSystemAdmin(operator)) {
            throw new AccessDeniedException("只有管理员可以永久删除广播");
        }
        Broadcast broadcast = requireBroadcastForUpdate(broadcastId);
        if (!"CANCELLED".equals(broadcast.getStatus())) {
            throw new IllegalArgumentException("请先撤销广播，再执行永久删除");
        }
        List<BroadcastReceiver> receivers = receiverMapper.selectByBroadcastId(broadcastId);
        List<Long> receiverIds = receivers == null ? List.of() : receivers.stream()
                .map(BroadcastReceiver::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (evidenceMapper != null) {
            evidenceMapper.delete(new LambdaQueryWrapper<BroadcastEvidence>()
                    .eq(BroadcastEvidence::getBroadcastId, broadcastId));
        }
        receiverMapper.delete(new LambdaQueryWrapper<BroadcastReceiver>()
                .eq(BroadcastReceiver::getBroadcastId, broadcastId));
        if (broadcastMapper.deleteById(broadcastId) != 1) {
            throw new IllegalStateException("广播删除失败");
        }
        return new BroadcastDeleteResult(broadcast, receiverIds);
    }

    @Override
    @Transactional
    public BroadcastReceiver complete(
            Long broadcastId,
            Long userId,
            String deviceType,
            BroadcastCompleteDTO request
    ) {
        Broadcast broadcast = requireBroadcastForUpdate(broadcastId);
        User user = requireActiveUser(userId);
        if (isSystemAdmin(user)) throw new AccessDeniedException("管理员不能替代接收者完成广播");
        if (!"ACTIVE".equals(broadcast.getStatus())) throw new IllegalArgumentException("当前广播已结束，不能继续提交");
        if (isExpired(broadcast, LocalDateTime.now())) throw new IllegalArgumentException("已超过截止时间，不可提交");
        BroadcastReceiver receiver = requireReceiver(broadcastId, userId);
        if (!"ACTIVE".equals(receiver.getTargetStatus())) throw new IllegalArgumentException("你已不在广播的目标接收者中");
        if ("EXECUTED".equals(receiver.getConfirmStatus()) && receiver.getCompletedAt() != null) return receiver;
        BroadcastCompleteDTO safeRequest = request == null ? new BroadcastCompleteDTO() : request;
        List<FileMetadata> images = validateImageFiles(safeRequest.getImageFileIds(), userId);
        if (Boolean.TRUE.equals(broadcast.getRequireImageProof()
        ) && images.isEmpty()) {
            throw new IllegalArgumentException("请上传图片后再提交");
        }
        if (Boolean.TRUE.equals(broadcast.getRequireLocationProof()
        ) && safeRequest.getLocation() == null) {
            throw new IllegalArgumentException("请更新位置后再提交");
        }
        LocalDateTime now = LocalDateTime.now();
        // 防止重试时插入同一批证据
        evidenceMapper.delete(
                new LambdaQueryWrapper<BroadcastEvidence>()
                        .eq(BroadcastEvidence::getBroadcastId, broadcastId)
                        .eq(BroadcastEvidence::getReceiverId, receiver.getId())
        );
        for (FileMetadata image : images) {
            BroadcastEvidence evidence = new BroadcastEvidence();
            evidence.setBroadcastId(broadcastId);
            evidence.setReceiverId(receiver.getId());
            evidence.setUserId(userId);
            evidence.setEvidenceType("COMPLETION_IMAGE");
            evidence.setFileId(image.getId());
            evidence.setCreateTime(now);
            evidenceMapper.insert(evidence);
            // 广播创建者可查看上传的图片
            if (fileAccessGrantMapper != null) {
                fileAccessGrantMapper.grant(image.getId(), broadcast.getSenderId(), "BROADCAST_EVIDENCE");
            }
        }
        if (safeRequest.getLocation() != null) {
            validateLocation(safeRequest.getLocation());
            evidenceMapper.insert(locationEvidence(
                    broadcastId,
                    receiver.getId(),
                    userId,
                    "COMPLETION_LOCATION",
                    safeRequest.getLocation(),
                    now
            ));
        }
        receiver.setDeliveredAt(receiver.getDeliveredAt() == null ? now : receiver.getDeliveredAt());
        receiver.setViewedAt(receiver.getViewedAt() == null ? now : receiver.getViewedAt());
        receiver.setConfirmStatus("EXECUTED");
        receiver.setConfirmedAt(now);
        receiver.setCompletedAt(now);
        receiver.setConfirmDeviceType(normalizeDeviceType(deviceType));
        receiver.setUpdateTime(now);
        if (receiverMapper.updateById(receiver) != 1) throw new IllegalArgumentException("广播完成状态保存失败");
        refreshBroadcastCompletion(broadcast, now);
        return receiver;
    }

    @Override
    public List<Broadcast> listVisible(Long userId) {
        User user = requireActiveUser(userId);
        if (isSystemAdmin(user)) {
            List<Broadcast> broadcasts = broadcastMapper.selectList(
                    new LambdaQueryWrapper<Broadcast>()
                            .orderByDesc(Broadcast::getCreateTime)
                            .orderByDesc(Broadcast::getId)
                            .last("LIMIT 200"));
            return broadcasts == null ? List.of() : broadcasts;
        }
        List<Broadcast> broadcasts = broadcastMapper.selectVisible(userId);
        return broadcasts == null ? List.of() : broadcasts;
    }

    @Override
    public List<Broadcast> listPending(Long userId) {
        User user = requireActiveUser(userId);
        if (isSystemAdmin(user)) return List.of();
        List<Broadcast> broadcasts = broadcastMapper.selectPending(userId);
        return broadcasts == null ? List.of() : broadcasts;
    }

    @Override
    public BroadcastDetailDTO getDetail(Long broadcastId, Long userId) {
        Broadcast broadcast = requireBroadcast(broadcastId);
        User user = requireActiveUser(userId);
        BroadcastReceiver receiver = isSystemAdmin(user) ? null : receiverMapper.selectReceiver(broadcastId, userId);
        boolean createdByCurrentUser = Objects.equals(broadcast.getSenderId(), userId);
        if (!createdByCurrentUser && !isSystemAdmin(user) && receiver == null) {
            throw new AccessDeniedException("无权查看该广播");
        }
        User sender = userMapper.selectById(broadcast.getSenderId());
        BroadcastSenderDTO senderDTO = sender == null
                ? new BroadcastSenderDTO(broadcast.getSenderId(), "unknown", "已注销用户", null)
                : new BroadcastSenderDTO(sender.getId(), sender.getUsername(), sender.getNickname(), sender.getAvatar());
        BroadcastContentEvidenceDTO contentEvidence = contentEvidence(broadcastId);
        return new BroadcastDetailDTO(
                broadcast,
                receiver,
                senderDTO,
                contentEvidence,
                readConfirmationOptions(broadcast.getConfirmationOptions()),
                createdByCurrentUser
        );
    }

    @Override
    public List<Long> getReceiverIds(Long broadcastId) {
        requireBroadcast(broadcastId);
        List<BroadcastReceiver> receivers = receiverMapper.selectByBroadcastId(broadcastId);
        if (receivers == null) return List.of();
        return receivers.stream()
                .map(BroadcastReceiver::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public BroadcastReceiver markDelivered(Long broadcastId, Long userId) {
        requireBroadcast(broadcastId);
        requireReceiver(broadcastId, userId);
        receiverMapper.markDelivered(broadcastId, userId);
        return requireReceiver(broadcastId, userId);
    }

    @Override
    public BroadcastReceiver markViewed(Long broadcastId, Long userId) {
        requireBroadcast(broadcastId);
        requireReceiver(broadcastId, userId);
        receiverMapper.markViewed(broadcastId, userId);
        return requireReceiver(broadcastId, userId);
    }

    @Override
    @Transactional
    public BroadcastReceiver confirm(Long broadcastId,
                                     Long userId,
                                     String deviceType,
                                     BroadcastConfirmDTO request) {
        // Cancellation and confirmation lock the same broadcast row. If a cancellation
        // commits first, this read observes CANCELLED and no confirmation can be written.
        Broadcast broadcast = requireBroadcastForUpdate(broadcastId);
        User user = requireActiveUser(userId);
        if (isSystemAdmin(user)) throw new AccessDeniedException("管理员仅查看广播统计，无需提交回执");
        BroadcastReceiver receiver = requireReceiver(broadcastId, userId);
        if (request == null) throw new IllegalArgumentException("广播确认参数不能为空");
        String requestedStatus = normalizeConfirmationValue(request.getStatus());

        // A retry of an already committed confirmation remains successful, even after the deadline.
        if (receiver.getConfirmedAt() != null && requestedStatus.equals(receiver.getConfirmStatus())) {
            return receiver;
        }
        if (!Boolean.TRUE.equals(broadcast.getConfirmationRequired())) {
            throw new IllegalArgumentException("该广播无需确认");
        }
        if ("EXECUTED".equals(requestedStatus)
                && (Boolean.TRUE.equals(broadcast.getRequireImageProof())
                || Boolean.TRUE.equals(broadcast.getRequireLocationProof()))) {
            throw new IllegalArgumentException("该广播要求提交完成证据，请使用完成操作");
        }
        if (!"PENDING".equals(receiver.getConfirmStatus())
                && !("NEED_SUPPORT".equals(receiver.getConfirmStatus())
                && "EXECUTED".equals(requestedStatus))) {
            throw new IllegalArgumentException("广播已经确认，不能修改确认结果");
        }
        if (!"ACTIVE".equals(broadcast.getStatus())) {
            throw new IllegalArgumentException("该广播当前不可确认");
        }
        if (isExpired(broadcast, LocalDateTime.now())) {
            throw new IllegalArgumentException("广播确认已过截止时间");
        }
        List<String> options = readConfirmationOptions(broadcast.getConfirmationOptions());
        if (!options.contains(requestedStatus)) {
            throw new IllegalArgumentException("广播确认选项无效");
        }

        String safeDeviceType = normalizeDeviceType(deviceType);
        int updated = receiverMapper.confirmIfPending(
                broadcastId, userId, requestedStatus, safeDeviceType);
        BroadcastReceiver current = requireReceiver(broadcastId, userId);
        if (updated == 1 || requestedStatus.equals(current.getConfirmStatus())) {
            if ("EXECUTED".equals(current.getConfirmStatus())) {
                current.setCompletedAt(current.getConfirmedAt());
                refreshBroadcastCompletion(broadcast, LocalDateTime.now());
            }
            return current;
        }
        throw new IllegalArgumentException("广播已经使用其他结果确认");
    }

    @Override
    public BroadcastStatsDTO getStats(Long broadcastId, Long userId) {
        Broadcast broadcast = requireBroadcast(broadcastId);
        User user = requireActiveUser(userId);
        if (!Objects.equals(broadcast.getSenderId(), userId) && !isSystemAdmin(user)) {
            throw new AccessDeniedException("只有广播创建者或管理员可以查看统计");
        }

        List<BroadcastReceiver> allReceivers = receiverMapper.selectByBroadcastId(broadcastId);
        if (allReceivers == null) allReceivers = List.of();
        List<BroadcastReceiver> receivers = allReceivers.stream()
                .filter(item -> !"REMOVED".equals(item.getTargetStatus()))
                .toList();
        long targetCount = receivers.size();
        long deliveredCount = receivers.stream().filter(item -> item.getDeliveredAt() != null).count();
        long viewedCount = receivers.stream().filter(item -> item.getViewedAt() != null).count();
        long confirmedCount = receivers.stream().filter(item -> item.getConfirmedAt() != null).count();
        long unconfirmedCount = Boolean.TRUE.equals(broadcast.getConfirmationRequired())
                ? targetCount - confirmedCount
                : 0;
        List<Long> unconfirmedUserIds = Boolean.TRUE.equals(broadcast.getConfirmationRequired())
                ? receivers.stream()
                        .filter(item -> item.getConfirmedAt() == null)
                        .map(BroadcastReceiver::getUserId)
                        .filter(Objects::nonNull)
                        .toList()
                : List.of();
        boolean expired = isExpired(broadcast, LocalDateTime.now());
        long expiredCount = expired && Boolean.TRUE.equals(broadcast.getConfirmationRequired())
                ? unconfirmedCount
                : 0;
        long executedCount = receivers.stream().filter(item -> "EXECUTED".equals(item.getConfirmStatus())).count();
        long needSupportCount = receivers.stream().filter(item -> "NEED_SUPPORT".equals(item.getConfirmStatus())).count();
        long removedCount = allReceivers.stream().filter(item -> "REMOVED".equals(item.getTargetStatus())).count();

        Map<String, Long> confirmationCounts = new LinkedHashMap<>();
        for (String option : readConfirmationOptions(broadcast.getConfirmationOptions())) {
            confirmationCounts.put(option, 0L);
        }
        for (BroadcastReceiver receiver : receivers) {
            if (receiver.getConfirmedAt() != null && StringUtils.hasText(receiver.getConfirmStatus())) {
                confirmationCounts.merge(receiver.getConfirmStatus(), 1L, Long::sum);
            }
        }

        return new BroadcastStatsDTO(
                broadcastId,
                targetCount,
                deliveredCount,
                viewedCount,
                confirmedCount,
                unconfirmedCount,
                executedCount,
                needSupportCount,
                removedCount,
                unconfirmedUserIds,
                expiredCount,
                expired,
                Map.copyOf(confirmationCounts)
        );
    }

    @Override
    public List<BroadcastRecipientDetailDTO> listRecipients(Long broadcastId, Long operatorId, String bucket) {
        Broadcast broadcast = requireManagedBroadcast(broadcastId, operatorId, false);
        List<BroadcastReceiver> receivers = receiverMapper.selectByBroadcastId(broadcast.getId());
        if (receivers == null || receivers.isEmpty()) return List.of();
        List<Long> userIds = receivers.stream().map(BroadcastReceiver::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream().collect(Collectors.toMap(User::getId, item -> item));
        String normalizedBucket = bucket == null ? "ALL" : bucket.trim().toUpperCase(Locale.ROOT);
        return receivers.stream().filter(receiver -> matchesBucket(receiver, normalizedBucket))
                .map(receiver -> recipientDetail(broadcastId, receiver, userMap.get(receiver.getUserId())))
                .toList();
    }

    public boolean matchesBucket(BroadcastReceiver receiver, String bucket) {
        return switch (bucket) {
            case "TARGET" -> !"REMOVED".equals(receiver.getTargetStatus());
            case "DELIVERED" -> receiver.getDeliveredAt() != null && !"REMOVED".equals(receiver.getTargetStatus());
            case "VIEWED" -> receiver.getViewedAt() != null && !"REMOVED".equals(receiver.getTargetStatus());
            case "EXECUTED" -> "EXECUTED".equals(receiver.getConfirmStatus());
            case "PENDING" -> !"REMOVED".equals(receiver.getTargetStatus()) && !"EXECUTED".equals(receiver.getConfirmStatus());
            case "NEED_SUPPORT" -> "NEED_SUPPORT".equals(receiver.getConfirmStatus()) && !"REMOVED".equals(receiver.getTargetStatus());
            case "REMOVED" -> "REMOVED".equals(receiver.getTargetStatus());
            default -> true;
        };
    }

    private BroadcastRecipientDetailDTO recipientDetail(Long broadcastId, BroadcastReceiver receiver, User user) {
        List<BroadcastEvidence> evidence = evidenceMapper == null
                ? List.of()
                : evidenceMapper.selectByReceiver(broadcastId, receiver.getId());
        List<String> imageUrls = evidence.stream().filter(item -> "COMPLETION_IMAGE".equals(item.getEvidenceType()))
                .map(BroadcastEvidence::getFileId)
                .filter(Objects::nonNull)
                .map(fileMetadataMapper::selectById)
                .filter(Objects::nonNull)
                .map(file -> fileService.getFileUrl(file.getFilePath()))
                .toList();
        BroadcastLocationDTO location = evidence.stream().filter(item -> "COMPLETION_LOCATION".equals(item.getEvidenceType()))
                .findFirst()
                .map(this::toLocationDTO)
                .orElse(null);
        return new BroadcastRecipientDetailDTO(
                receiver.getId(),
                receiver.getUserId(),
                user == null ? "unknown" : user.getUsername(),
                user == null ? "已注销用户" : user.getNickname(),
                user == null ? null : user.getAvatar(),
                receiver.getTargetStatus(),
                receiver.getConfirmStatus(),
                receiver.getDeliveredAt(),
                receiver.getViewedAt(),
                receiver.getCompletedAt(),
                imageUrls,
                location,
                receiver.getRemindCount() == null ? 0 : receiver.getRemindCount(),
                receiver.getLastRemindedAt()
        );
    }

    private BroadcastContentEvidenceDTO contentEvidence(Long broadcastId) {
        if (evidenceMapper == null || fileMetadataMapper == null || fileService == null) {
            return new BroadcastContentEvidenceDTO(List.of(), null);
        }
        List<BroadcastEvidence> evidence = evidenceMapper.selectContentEvidence(broadcastId);
        if (evidence == null) evidence = List.of();
        List<String> imageUrls = evidence.stream()
                .filter(item -> "CONTENT_IMAGE".equals(item.getEvidenceType()))
                .map(BroadcastEvidence::getFileId)
                .filter(Objects::nonNull)
                .map(fileMetadataMapper::selectById)
                .filter(Objects::nonNull)
                .map(file -> fileService.getFileUrl(file.getFilePath()))
                .toList();
        BroadcastLocationDTO location = evidence.stream()
                .filter(item -> "CONTENT_LOCATION".equals(item.getEvidenceType()))
                .findFirst().map(this::toLocationDTO).orElse(null);
        return new BroadcastContentEvidenceDTO(imageUrls, location);
    }

    @Override
    @Transactional
    public void remindReceiver(Long broadcastId, Long receiverUserId, Long operatorId) {
        Broadcast broadcast = requireManagedBroadcast(broadcastId, operatorId, true);
        BroadcastReceiver receiver = requireReceiver(broadcastId, receiverUserId);
        if (!"ACTIVE".equals(receiver.getTargetStatus())) throw new IllegalArgumentException("该用户已不再目标范围内");
        if ("EXECUTED".equals(receiver.getConfirmStatus())) throw new IllegalArgumentException("该用户已经完成广播");
        LocalDateTime now = LocalDateTime.now();
        if (receiver.getLastRemindedAt() != null && receiver.getLastRemindedAt().plusMinutes(5).isAfter(now)) throw new IllegalArgumentException("五分钟内不能重复提醒同一用户");
        receiver.setRemindCount((receiver.getRemindCount() == null ? 0 : receiver.getRemindCount()) + 1);
        receiver.setLastRemindedAt(now);
        receiver.setUpdateTime(now);
        receiverMapper.updateById(receiver);
    }

    private Broadcast requireManagedBroadcast(Long broadcastId, Long operatorId, boolean activeRequired) {
        Broadcast broadcast = activeRequired ? requireBroadcastForUpdate(broadcastId) : requireBroadcast(broadcastId);
        User operator = requireActiveUser(operatorId);
        boolean owner = Objects.equals(broadcast.getSenderId(), operatorId);
        if (!owner && !isSystemAdmin(operator)) throw new AccessDeniedException("只有广播创建者或管理员可以管理");
        if (activeRequired && !"ACTIVE".equals(broadcast.getStatus())) throw new IllegalArgumentException("当前广播不能继续管理");
        return broadcast;
    }

    @Override
    @Transactional
    public BroadcastTargetUpdateResultDTO updateTargets(Long broadcastId,
                                                         Long operatorId,
                                                         BroadcastTargetUpdateDTO request) {
        Broadcast broadcast = requireManagedBroadcast(broadcastId, operatorId, true);
        Set<Long> addIds = cleanIds(request == null ? null : request.getAddUserIds());
        Set<Long> removeIds = cleanIds(request == null ? null : request.getRemoveUserIds());
        if (!Collections.disjoint(addIds, removeIds)) {
            throw new IllegalArgumentException("同一用户不能同时新增和移出");
        }
        User sender = requireActiveUser(broadcast.getSenderId());
        validateAdditionalRecipients(sender, broadcast.getScopeType(), addIds);
        LocalDateTime now = LocalDateTime.now();
        List<Long> added = new ArrayList<>();
        List<Long> removed = new ArrayList<>();
        for (Long userId : addIds) {
            BroadcastReceiver existing = receiverMapper.selectReceiverIncludingRemoved(broadcastId, userId);
            if (existing == null) {
                BroadcastReceiver receiver = new BroadcastReceiver();
                receiver.setBroadcastId(broadcastId);
                receiver.setUserId(userId);
                receiver.setTargetStatus("ACTIVE");
                receiver.setConfirmStatus(Boolean.TRUE.equals(broadcast.getConfirmationRequired()) ? "PENDING" : "NOT_REQUIRED");
                receiver.setRemindCount(0);
                receiver.setCreateTime(now);
                receiver.setUpdateTime(now);
                if (receiverMapper.insert(receiver) != 1) throw new IllegalStateException("新增目标用户失败");
            } else if ("REMOVED".equals(existing.getTargetStatus())) {
                existing.setTargetStatus("ACTIVE");
                existing.setConfirmStatus(Boolean.TRUE.equals(broadcast.getConfirmationRequired()) ? "PENDING" : "NOT_REQUIRED");
                existing.setConfirmedAt(null);
                existing.setCompletedAt(null);
                existing.setRemovedAt(null);
                existing.setRemovedBy(null);
                existing.setUpdateTime(now);
                receiverMapper.updateById(existing);
            } else {
                continue;
            }
            added.add(userId);
        }
        for (Long userId : removeIds) {
            BroadcastReceiver receiver = receiverMapper.selectReceiverIncludingRemoved(broadcastId, userId);
            if (receiver == null || "REMOVED".equals(receiver.getTargetStatus())) continue;
            if ("EXECUTED".equals(receiver.getConfirmStatus())) {
                throw new IllegalArgumentException("已执行用户不能移出目标范围");
            }
            receiver.setTargetStatus("REMOVED");
            receiver.setRemovedAt(now);
            receiver.setRemovedBy(operatorId);
            receiver.setUpdateTime(now);
            receiverMapper.updateById(receiver);
            removed.add(userId);
        }
        refreshBroadcastCompletion(broadcast, now);
        return new BroadcastTargetUpdateResultDTO(List.copyOf(added), List.copyOf(removed));
    }

    private Set<Long> cleanIds(List<Long> source) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (source != null) source.stream().filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }

    private void validateAdditionalRecipients(User sender, String scope, Set<Long> userIds) {
        if (userIds.isEmpty()) return;
        if (userIds.size() > MAX_EXPLICIT_RECEIVERS) throw new IllegalArgumentException("单次最多指定" + MAX_EXPLICIT_RECEIVERS + "名接收者");
        if ("ALL".equals(scope)) {
            List<User> users = userMapper.selectBatchIds(userIds);
            if (users == null || userIds(users).size() != userIds.size()) throw new IllegalArgumentException("新增接收者包含不可接收账号");
            return;
        }
        friendReceivers(sender.getId(), new ArrayList<>(userIds));
    }

    private Set<Long> resolveReceivers(User sender, String scope, BroadcastCreateDTO request) {
        boolean admin = isSystemAdmin(sender);
        return switch (scope) {
            case "ALL" -> {
                if (!admin) throw new AccessDeniedException("只有管理员可以向全部用户发送广播");
                List<User> activeUsers = userMapper.selectList(
                        new LambdaQueryWrapper<User>()
                                .eq(User::getStatus, 1)
                                .ne(User::getUsername, "admin")
                                .orderByAsc(User::getId));
                yield userIds(activeUsers);
            }
            case "USERS" -> friendReceivers(sender.getId(), request.getReceiverIds());
            default -> throw new IllegalArgumentException("广播范围无效");
        };
    }

    private Set<Long> friendReceivers(Long senderId, List<Long> requestedIds) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (requestedIds != null) {
            for (Long id : requestedIds) if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("请至少指定一名广播接收者");
        if (ids.size() > MAX_EXPLICIT_RECEIVERS) {
            throw new IllegalArgumentException("单次最多指定" + MAX_EXPLICIT_RECEIVERS + "名接收者");
        }

        List<User> users = userMapper.selectBatchIds(ids);
        LinkedHashSet<Long> activeIds = new LinkedHashSet<>();
        if (users != null) {
            users.stream()
                    .filter(Objects::nonNull)
                    .filter(user -> user.getId() != null)
                    .filter(user -> Integer.valueOf(1).equals(user.getStatus()))
                    .filter(user -> !isSystemAdmin(user))
                    .map(User::getId)
                    .forEach(activeIds::add);
        }
        if (activeIds.size() != ids.size() || !activeIds.containsAll(ids)) {
            throw new IllegalArgumentException("广播接收者包含不存在、已停用或不可接收的账号");
        }
        for (Long receiverId : ids) {
            if (!friendService.isFriend(senderId, receiverId)
                    || friendService.isBlockedBy(senderId, receiverId)) {
                throw new AccessDeniedException("广播接收者只能选择自己的有效好友");
            }
        }
        return ids;
    }

    private LinkedHashSet<Long> userIds(Collection<User> users) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (users == null) return result;
        for (User user : users) {
            if (user != null
                    && user.getId() != null
                    && Integer.valueOf(1).equals(user.getStatus())
                    && !isSystemAdmin(user)) {
                result.add(user.getId());
            }
        }
        return result;
    }

    private List<String> normalizeConfirmationOptions(boolean required, List<String> requested) {
        if (!required) return List.of();
        List<String> source = requested == null || requested.isEmpty()
                ? DEFAULT_CONFIRMATION_OPTIONS
                : requested;
        LinkedHashSet<String> options = new LinkedHashSet<>();
        for (String value : source) {
            String normalized = normalizeConfirmationValue(value);
            if (normalized.length() > 32) throw new IllegalArgumentException("广播确认选项不能超过32个字符");
            if ("PENDING".equals(normalized) || "NOT_REQUIRED".equals(normalized)) {
                throw new IllegalArgumentException("广播确认选项使用了保留值");
            }
            options.add(normalized);
        }
        if (options.size() > MAX_CONFIRMATION_OPTIONS) {
            throw new IllegalArgumentException("广播确认选项不能超过" + MAX_CONFIRMATION_OPTIONS + "个");
        }
        return List.copyOf(options);
    }

    private String writeConfirmationOptions(List<String> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("广播确认选项序列化失败", exception);
        }
    }

    private List<String> readConfirmationOptions(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            return values == null ? List.of() : List.copyOf(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("广播确认选项数据损坏", exception);
        }
    }

    private Broadcast requireBroadcast(Long broadcastId) {
        if (broadcastId == null) throw new IllegalArgumentException("广播标识不能为空");
        Broadcast broadcast = broadcastMapper.selectById(broadcastId);
        if (broadcast == null) throw new IllegalArgumentException("广播不存在");
        return broadcast;
    }

    private Broadcast requireBroadcastForUpdate(Long broadcastId) {
        if (broadcastId == null) throw new IllegalArgumentException("广播标识不能为空");
        Broadcast broadcast = broadcastMapper.selectByIdForUpdate(broadcastId);
        if (broadcast == null) throw new IllegalArgumentException("广播不存在");
        return broadcast;
    }

    private BroadcastReceiver requireReceiver(Long broadcastId, Long userId) {
        if (userId == null) throw new AccessDeniedException("请先登录");
        BroadcastReceiver receiver = receiverMapper.selectReceiver(broadcastId, userId);
        if (receiver == null) throw new AccessDeniedException("当前用户不是该广播接收者");
        return receiver;
    }

    private User requireActiveUser(Long userId) {
        if (userId == null) throw new AccessDeniedException("请先登录");
        User user = userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new AccessDeniedException("账号不存在或已停用");
        }
        return user;
    }

    private boolean isSystemAdmin(User user) {
        return user != null && "admin".equals(user.getUsername());
    }

    private boolean isExpired(Broadcast broadcast, LocalDateTime now) {
        return broadcast.getDeadlineAt() != null && !broadcast.getDeadlineAt().isAfter(now);
    }

    private String normalizedEnum(String value,
                                  String defaultValue,
                                  List<String> allowed,
                                  String errorMessage) {
        String normalized = StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : defaultValue;
        if (normalized == null || !allowed.contains(normalized)) {
            throw new IllegalArgumentException(errorMessage);
        }
        return normalized;
    }

    private String normalizeConfirmationValue(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("广播确认选项不能为空");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeDeviceType(String deviceType) {
        if (!StringUtils.hasText(deviceType)) return "unknown";
        String normalized = deviceType.trim();
        return normalized.length() <= 50 ? normalized : normalized.substring(0, 50);
    }

    private String requiredText(String value, String fieldName, int maxLength) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(fieldName + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private void saveContentEvidence(
            Broadcast broadcast,
            User sender,
            List<Long> imageFileIds,
            BroadcastLocationDTO location,
            LocalDateTime now
    ) {
        List<FileMetadata> images = validateImageFiles(imageFileIds, sender.getId());
        for (FileMetadata image : images) {
            BroadcastEvidence evidence = new BroadcastEvidence();
            evidence.setBroadcastId(broadcast.getId());
            evidence.setReceiverId(null);
            evidence.setUserId(sender.getId());
            evidence.setEvidenceType("CONTENT_IMAGE");
            evidence.setFileId(image.getId());
            evidence.setCreateTime(now);
            evidenceMapper.insert(evidence);
        }
        if (location != null) {
            validateLocation(location);
            BroadcastEvidence evidence = locationEvidence(
                    broadcast.getId(),
                    null,
                    sender.getId(),
                    "CONTENT_LOCATION",
                    location,
                    now
            );
            evidenceMapper.insert(evidence);
        }
    }

    private List<FileMetadata> validateImageFiles(List<Long> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) return List.of();
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        for (Long fileId : fileIds) {
            if (fileId != null) uniqueIds.add(fileId);
        }
        if (uniqueIds.size() > 3) throw new IllegalArgumentException("最多上传3张图片");
        List<FileMetadata> files = fileMetadataMapper.selectBatchIds(uniqueIds);
        if (files == null || files.size() != uniqueIds.size()) throw new IllegalArgumentException("完成图片不存在");
        for (FileMetadata file : files) {
            if (!Objects.equals(file.getUploadUserId(), userId)) throw new AccessDeniedException("只能使用自己上传的照片");
            if (!StringUtils.hasText(file.getFileType()) || !file.getFileType().startsWith("image/")) throw new IllegalArgumentException("请上传图片");
        }
        return files;
    }

    private void grantContentImagesToReceiver(List<Long> imageFileIds, Long receiverId) {
        if (fileAccessGrantMapper == null || imageFileIds == null || receiverId == null) return;
        imageFileIds.stream().filter(Objects::nonNull).distinct()
                .forEach(fileId -> fileAccessGrantMapper.grant(fileId, receiverId, "BROADCAST_CONTENT"));
    }

    private void validateLocation(BroadcastLocationDTO location) {
        if (location.getLatitude() == null || location.getLongitude() == null) throw new IllegalArgumentException("定位数据不完整");
        BigDecimal latitude = location.getLatitude();
        BigDecimal longitude = location.getLongitude();
        if (latitude.compareTo(new BigDecimal("-90")) < 0 || latitude.compareTo(new BigDecimal("90")) > 0) {
            throw new IllegalArgumentException("维度超出有效范围");
        }
        if (longitude.compareTo(new BigDecimal("-180")) < 0 || longitude.compareTo(new BigDecimal("180")) > 0) {
            throw new IllegalArgumentException("经度超出有效范围");
        }
        if (location.getAccuracyMeters() != null && location.getAccuracyMeters().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("定位精度无效");
        }
    }

    private BroadcastEvidence locationEvidence(
            Long broadcastId,
            Long receiverId,
            Long userId,
            String evidenceType,
            BroadcastLocationDTO location,
            LocalDateTime now
    ) {
        BroadcastEvidence evidence = new BroadcastEvidence();
        evidence.setBroadcastId(broadcastId);
        evidence.setReceiverId(receiverId);
        evidence.setUserId(userId);
        evidence.setEvidenceType(evidenceType);
        evidence.setLatitude(location.getLatitude());
        evidence.setLongitude(location.getLongitude());
        evidence.setAccuracyMeters(location.getAccuracyMeters());
        evidence.setAddressText(StringUtils.hasText(location.getAddressText()) ? location.getAddressText().trim() : null);
        evidence.setCapturedAt(location.getCapturedAt() == null ? now : location.getCapturedAt());
        evidence.setCreateTime(now);
        return evidence;
    }

    private BroadcastLocationDTO toLocationDTO(BroadcastEvidence evidence) {
        BroadcastLocationDTO location = new BroadcastLocationDTO();
        location.setLatitude(evidence.getLatitude());
        location.setLongitude(evidence.getLongitude());
        location.setAccuracyMeters(evidence.getAccuracyMeters());
        location.setAddressText(evidence.getAddressText());
        location.setCapturedAt(evidence.getCapturedAt());
        return location;
    }

    private void refreshBroadcastCompletion(Broadcast broadcast, LocalDateTime now) {
        List<BroadcastReceiver> receivers = receiverMapper.selectByBroadcastId(broadcast.getId());
        List<BroadcastReceiver> activeReceiver = receivers == null ? List.of() : receivers.stream().filter(item -> !"REMOVED".equals(item.getTargetStatus())).toList();
        if (activeReceiver.isEmpty()) return;
        boolean allExecuted = activeReceiver.stream().allMatch(item -> "EXECUTED".equals(item.getConfirmStatus()));
        if (!allExecuted) return;
        broadcast.setStatus("COMPLETED");
        broadcast.setCompletedAt(now);
        broadcast.setUpdateTime(now);
        if (broadcastMapper.updateById(broadcast) != 1) throw new IllegalArgumentException("广播状态更新失败");
    }
}
