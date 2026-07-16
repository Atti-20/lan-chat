package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.BroadcastConfirmDTO;
import com.lanchat.dto.BroadcastCreateDTO;
import com.lanchat.dto.BroadcastDetailDTO;
import com.lanchat.dto.BroadcastStatsDTO;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;
import com.lanchat.entity.User;
import com.lanchat.mapper.BroadcastMapper;
import com.lanchat.mapper.BroadcastReceiverMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.BroadcastService;
import com.lanchat.service.FriendService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class BroadcastServiceImpl implements BroadcastService {

    private static final List<String> PRIORITIES = List.of("NORMAL", "IMPORTANT", "EMERGENCY");
    private static final List<String> SCOPES = List.of("ALL", "USERS");
    private static final List<String> DEFAULT_CONFIRMATION_OPTIONS =
            List.of("RECEIVED", "EXECUTED", "NEED_SUPPORT");
    private static final int MAX_EXPLICIT_RECEIVERS = 500;
    private static final int MAX_CONFIRMATION_OPTIONS = 10;

    private final BroadcastMapper broadcastMapper;
    private final BroadcastReceiverMapper receiverMapper;
    private final UserMapper userMapper;
    private final FriendService friendService;
    private final ObjectMapper objectMapper;

    public BroadcastServiceImpl(BroadcastMapper broadcastMapper,
                                BroadcastReceiverMapper receiverMapper,
                                UserMapper userMapper,
                                FriendService friendService,
                                ObjectMapper objectMapper) {
        this.broadcastMapper = broadcastMapper;
        this.receiverMapper = receiverMapper;
        this.userMapper = userMapper;
        this.friendService = friendService;
        this.objectMapper = objectMapper;
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
        if (broadcastMapper.insert(broadcast) != 1 || broadcast.getId() == null) {
            throw new IllegalStateException("广播创建失败");
        }

        for (Long receiverId : receiverIds) {
            BroadcastReceiver receiver = new BroadcastReceiver();
            receiver.setBroadcastId(broadcast.getId());
            receiver.setUserId(receiverId);
            receiver.setConfirmStatus(confirmationRequired ? "PENDING" : "NOT_REQUIRED");
            receiver.setCreateTime(now);
            receiver.setUpdateTime(now);
            if (receiverMapper.insert(receiver) != 1) {
                throw new IllegalStateException("广播接收记录创建失败");
            }
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
        return new BroadcastDetailDTO(
                broadcast,
                receiver,
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
        if (!"PENDING".equals(receiver.getConfirmStatus())) {
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
        if (updated == 1 || requestedStatus.equals(current.getConfirmStatus())) return current;
        throw new IllegalArgumentException("广播已经使用其他结果确认");
    }

    @Override
    public BroadcastStatsDTO getStats(Long broadcastId, Long userId) {
        Broadcast broadcast = requireBroadcast(broadcastId);
        User user = requireActiveUser(userId);
        if (!Objects.equals(broadcast.getSenderId(), userId) && !isSystemAdmin(user)) {
            throw new AccessDeniedException("只有广播创建者或管理员可以查看统计");
        }

        List<BroadcastReceiver> receivers = receiverMapper.selectByBroadcastId(broadcastId);
        if (receivers == null) receivers = List.of();
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
                unconfirmedUserIds,
                expiredCount,
                expired,
                Map.copyOf(confirmationCounts)
        );
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
}
