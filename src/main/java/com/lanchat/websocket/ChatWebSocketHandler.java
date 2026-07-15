package com.lanchat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.common.ConversationIds;
import com.lanchat.common.FileReferenceUtil;
import com.lanchat.dto.ReliableMessageResult;
import com.lanchat.dto.WebSocketEnvelope;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.ChatMessageService;
import com.lanchat.service.ConversationService;
import com.lanchat.service.FileService;
import com.lanchat.service.FriendService;
import com.lanchat.service.GroupService;
import com.lanchat.service.UserService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * LanChat WebSocket V1 可靠消息处理器。
 *
 * <p>握手阶段只执行 Origin 校验；浏览器连接成功后必须在 10 秒内发送 AUTH。
 * 访问令牌不会出现在 URL 或代理访问日志中。所有请求型事件使用统一信封，
 * CHAT_ACK 只会在消息事务提交后返回。</p>
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private static final String USER_ID_ATTRIBUTE = "authenticatedUserId";
    private static final String DEVICE_ID_ATTRIBUTE = "authenticatedDeviceId";
    private static final String DEVICE_TYPE_ATTRIBUTE = "authenticatedDeviceType";
    private static final String TOKEN_ATTRIBUTE = "authenticatedAccessToken";

    private static final Map<Long, Set<WebSocketSession>> ONLINE_SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Long, User> ONLINE_USERS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<Map<String, Object>> CONNECTION_EVENTS = new ConcurrentLinkedDeque<>();
    private static final LongAdder EVENT_COUNT = new LongAdder();
    private static final LongAdder EVENT_PROCESSING_NANOS = new LongAdder();
    private static final LongAdder ACK_COUNT = new LongAdder();
    private static final LongAdder FAILURE_COUNT = new LongAdder();
    private static final int MAX_CONNECTION_EVENTS = 50;

    private static final Set<String> BURN_FORBIDDEN_TYPES = Set.of("file", "voice");
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("text", "image", "file", "voice", "video");
    private static final int MAX_TEXT_LENGTH = 4_000;
    private static final int MAX_FRAME_LENGTH = 64_000;
    private static final int MAX_SYNC_CONVERSATIONS = 100;
    private static final int MAX_SYNC_MESSAGES = 200;

    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;
    private final ChatMessageService chatMessageService;
    private final ConversationService conversationService;
    private final FileService fileService;
    private final UserService userService;
    private final GroupService groupService;
    private final FriendService friendService;
    private final ScheduledExecutorService authTimeoutExecutor;

    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                JwtUtil jwtUtil,
                                ChatMessageService chatMessageService,
                                ConversationService conversationService,
                                FileService fileService,
                                UserService userService,
                                GroupService groupService,
                                FriendService friendService) {
        this.objectMapper = objectMapper;
        this.jwtUtil = jwtUtil;
        this.chatMessageService = chatMessageService;
        this.conversationService = conversationService;
        this.fileService = fileService;
        this.userService = userService;
        this.groupService = groupService;
        this.friendService = friendService;
        this.authTimeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lanchat-ws-auth-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdownExecutor() {
        authTimeoutExecutor.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        recordConnectionEvent("CONNECTED", session, null, null);
        authTimeoutExecutor.schedule(() -> {
            if (getUserId(session) == null && session.isOpen()) {
                sendEvent(session, envelope("ERROR", null, null, null,
                        errorPayload("AUTH_REQUIRED", "连接认证超时", false)));
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            }
        }, 10, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.getPayloadLength() > MAX_FRAME_LENGTH) {
            closeQuietly(session, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }

        WebSocketEnvelope incoming;
        try {
            incoming = objectMapper.readValue(message.getPayload(), WebSocketEnvelope.class);
        } catch (Exception exception) {
            sendEvent(session, envelope("ERROR", null, null, null,
                    errorPayload("BAD_FRAME", "消息格式无法解析", false)));
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }

        String event = normalizedEvent(incoming.getEvent());
        if (!Integer.valueOf(1).equals(incoming.getVersion())) {
            sendError(session, incoming, "UNSUPPORTED_VERSION", "协议版本不兼容，请升级客户端", false);
            return;
        }

        if (getUserId(session) == null) {
            if (!"AUTH".equals(event)) {
                sendError(session, incoming, "AUTH_REQUIRED", "请先完成连接认证", false);
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
            authenticate(session, incoming);
            return;
        }

        if (!ensureSessionActive(session, incoming)) return;

        long processingStarted = System.nanoTime();
        EVENT_COUNT.increment();
        try {
            switch (event) {
                case "PING" -> sendEvent(session, envelope(
                        "PONG", incoming.getRequestId(), null, null,
                        Map.of("serverTime", System.currentTimeMillis())));
                case "CHAT_SEND" -> handleChatSend(session, incoming);
                case "SYNC_REQUEST" -> handleSyncRequest(session, incoming);
                case "CHAT_READ" -> handleChatRead(session, incoming);
                case "CHAT_RECALL" -> handleRecall(session, incoming);
                case "CHAT_BURN" -> handleBurn(session, incoming);
                case "TYPING_START", "TYPING_STOP" -> handleTyping(session, incoming, event);
                case "CHAT_DELIVER" -> {
                    // MVP 以会话 lastReadSequence 聚合回执；精细送达记录在后续版本落表。
                }
                case "AUTH" -> sendError(session, incoming, "ALREADY_AUTHENTICATED", "连接已经认证", false);
                default -> sendError(session, incoming, "UNKNOWN_EVENT", "未知事件：" + event, false);
            }
        } catch (IllegalArgumentException exception) {
            sendError(session, incoming, "BUSINESS_REJECTED", exception.getMessage(), false);
        } catch (Exception exception) {
            log.error("WebSocket 事件处理失败: event={}, userId={}", event, getUserId(session), exception);
            sendError(session, incoming, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试", true);
        } finally {
            EVENT_PROCESSING_NANOS.add(System.nanoTime() - processingStarted);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = getUserId(session);
        recordConnectionEvent("CLOSED", session, userId,
                status.getCode() + ":" + status.getReason());
        if (userId == null) return;

        Set<WebSocketSession> sessions = ONLINE_SESSIONS.get(userId);
        if (sessions == null) return;
        boolean becameOffline = sessions.remove(session) && sessions.isEmpty();
        if (!becameOffline) return;

        ONLINE_SESSIONS.remove(userId, sessions);
        User user = ONLINE_USERS.remove(userId);
        userService.updateOnlineStatus(userId, 0);
        broadcastPresence("offline", userId, user == null ? "" : user.getNickname());
        sendOnlineListToAll();
        log.info("用户 {} 下线，当前在线人数: {}", userId, ONLINE_SESSIONS.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        FAILURE_COUNT.increment();
        recordConnectionEvent("TRANSPORT_ERROR", session, getUserId(session),
                exception == null ? null : exception.getClass().getSimpleName());
        log.warn("WebSocket 传输错误: userId={}, error={}", getUserId(session),
                exception == null ? "unknown" : exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private void authenticate(WebSocketSession session, WebSocketEnvelope incoming) {
        String token = payloadString(incoming, "token");
        if (!StringUtils.hasText(token) || !jwtUtil.isAccessToken(token)) {
            sendEvent(session, envelope("TOKEN_EXPIRED", incoming.getRequestId(), null, null,
                    Map.of("message", "访问令牌无效或已过期")));
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        try {
            Long userId = jwtUtil.getUserIdFromToken(token);
            String deviceType = jwtUtil.getDeviceTypeFromToken(token);
            User user = userService.getUserInfo(userId);
            DeviceLogin device = userService.getActiveDevice(token, userId, deviceType);
            if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || device == null) {
                sendEvent(session, envelope("FORCE_LOGOUT", incoming.getRequestId(), null, null,
                        Map.of("message", "设备会话已经失效")));
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }

            session.getAttributes().put(USER_ID_ATTRIBUTE, userId);
            session.getAttributes().put(DEVICE_ID_ATTRIBUTE, device.getId());
            session.getAttributes().put(DEVICE_TYPE_ATTRIBUTE, deviceType);
            session.getAttributes().put(TOKEN_ATTRIBUTE, token);

            Set<WebSocketSession> sessions = ONLINE_SESSIONS.computeIfAbsent(
                    userId, ignored -> ConcurrentHashMap.newKeySet());
            boolean wasOffline = sessions.isEmpty();
            sessions.add(session);
            ONLINE_USERS.put(userId, user);
            userService.updateOnlineStatus(userId, 1);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            payload.put("deviceId", device.getId());
            payload.put("serverTime", System.currentTimeMillis());
            sendEvent(session, envelope("AUTH_OK", incoming.getRequestId(), null, null, payload));

            if (wasOffline) {
                broadcastPresence("online", userId, user.getNickname());
                sendOnlineListToAll();
            } else {
                sendOnlineList(session);
            }
            log.info("用户 {} 完成 WebSocket 认证，设备 {}", userId, device.getId());
            recordConnectionEvent("AUTHENTICATED", session, userId, null);
        } catch (Exception exception) {
            FAILURE_COUNT.increment();
            recordConnectionEvent("AUTH_FAILED", session, null, exception.getClass().getSimpleName());
            sendEvent(session, envelope("TOKEN_EXPIRED", incoming.getRequestId(), null, null,
                    Map.of("message", "连接认证失败")));
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
        }
    }

    private boolean ensureSessionActive(WebSocketSession session, WebSocketEnvelope incoming) {
        Long userId = getUserId(session);
        String token = attributeString(session, TOKEN_ATTRIBUTE);
        String deviceType = attributeString(session, DEVICE_TYPE_ATTRIBUTE);
        if (jwtUtil.isAccessToken(token)
                && userService.isAccessTokenActive(token, userId, deviceType)) {
            return true;
        }

        String event = jwtUtil.isAccessToken(token) ? "FORCE_LOGOUT" : "TOKEN_EXPIRED";
        String message = "FORCE_LOGOUT".equals(event) ? "设备会话已经失效" : "访问令牌已过期";
        sendEvent(session, envelope(event, incoming.getRequestId(), null, null, Map.of("message", message)));
        closeQuietly(session, CloseStatus.POLICY_VIOLATION);
        return false;
    }

    private void handleChatSend(WebSocketSession session, WebSocketEnvelope incoming) {
        requireRequestId(incoming);
        Long senderId = getUserId(session);
        User sender = ONLINE_USERS.get(senderId);
        if (sender == null) throw new IllegalArgumentException("用户状态已失效");

        String clientMsgId = incoming.getClientMsgId();
        if (!StringUtils.hasText(clientMsgId)
                || !clientMsgId.matches("^[A-Za-z0-9_-]{8,64}$")) {
            throw new IllegalArgumentException("clientMsgId 无效");
        }

        Long toUserId = payloadLong(incoming, "toUserId");
        Long groupId = payloadLong(incoming, "groupId");
        validateTargetAndPermission(senderId, toUserId, groupId);

        String contentType = payloadString(incoming, "contentType");
        contentType = StringUtils.hasText(contentType)
                ? contentType.trim().toLowerCase(Locale.ROOT) : "text";
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("不支持的消息类型");
        }

        String content = payloadString(incoming, "content");
        if (content == null) content = "";
        if ("text".equals(contentType) && (content.isBlank() || content.length() > MAX_TEXT_LENGTH)) {
            throw new IllegalArgumentException(content.isBlank() ? "消息不能为空" : "消息不能超过 4000 个字符");
        }
        if (!"text".equals(contentType) && (content.isBlank() || content.length() > 30_000)) {
            throw new IllegalArgumentException("消息内容无效");
        }

        String replyToId = payloadString(incoming, "replyToId");
        if (StringUtils.hasText(replyToId)
                && (!replyToId.matches("^[A-Za-z0-9_-]{8,64}$")
                || !chatMessageService.canAccessMessage(replyToId, senderId))) {
            throw new IllegalArgumentException("引用的消息不存在或无权访问");
        }

        String mentionUserIds = payloadString(incoming, "mentionUserIds");
        if (StringUtils.hasText(mentionUserIds)
                && (mentionUserIds.length() > 500
                || !mentionUserIds.matches("^\\d+(?:,\\d+)*$"))) {
            throw new IllegalArgumentException("@成员参数无效");
        }

        boolean burn = payloadBoolean(incoming, "isBurn");
        if (burn && BURN_FORBIDDEN_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("文件和语音消息不支持阅后即焚");
        }

        ChatMessage message = new ChatMessage();
        message.setClientMsgId(clientMsgId);
        message.setFromUserId(senderId);
        message.setSenderDeviceId(attributeLong(session, DEVICE_ID_ATTRIBUTE));
        message.setToUserId(toUserId);
        message.setGroupId(groupId);
        message.setType(contentType);
        message.setContent(content);
        message.setReplyToId(StringUtils.hasText(replyToId) ? replyToId.trim() : null);
        message.setMentionUserIds(StringUtils.hasText(mentionUserIds) ? mentionUserIds : null);
        message.setIsBurn(burn ? 1 : 0);
        message.setBurnDuration(5);
        message.setIsRecalled(0);
        message.setStatus(0);
        message.setClientCreatedAt(clientCreatedAt(incoming));
        if (!"text".equals(contentType)) {
            String storedName = FileReferenceUtil.extractFirstStoredName(content);
            if (!StringUtils.hasText(storedName) || !fileService.canAccessFile(storedName, senderId)) {
                throw new IllegalArgumentException("附件不存在或无权访问");
            }
            message.setFilePath(storedName);
        }

        ReliableMessageResult result;
        try {
            result = chatMessageService.saveReliableMessage(message, incoming.getConversationId());
        } catch (DuplicateKeyException duplicate) {
            ChatMessage existing = chatMessageService.getByClientMsgId(senderId, clientMsgId);
            if (existing == null) throw duplicate;
            result = new ReliableMessageResult(existing, true);
        }

        ChatMessage saved = result.message();
        Map<String, Object> ackPayload = new LinkedHashMap<>();
        ackPayload.put("clientMsgId", saved.getClientMsgId());
        ackPayload.put("messageId", saved.getMessageId());
        ackPayload.put("conversationId", saved.getConversationId());
        ackPayload.put("sequence", saved.getSequence());
        ackPayload.put("serverTime", System.currentTimeMillis());
        ackPayload.put("duplicated", result.duplicated());
        sendEvent(session, envelope("CHAT_ACK", incoming.getRequestId(), saved.getClientMsgId(),
                saved.getConversationId(), ackPayload));
        ACK_COUNT.increment();

        if (!result.duplicated()) {
            WebSocketEnvelope deliver = envelope("CHAT_DELIVER", null, saved.getClientMsgId(),
                    saved.getConversationId(), messagePayload(saved, sender));
            deliverMessage(saved, deliver);
        }
    }

    private void handleSyncRequest(WebSocketSession session, WebSocketEnvelope incoming) {
        requireRequestId(incoming);
        Long userId = getUserId(session);
        int perConversationLimit = Math.max(1, Math.min(
                payloadLong(incoming, "limit") == null ? 100 : payloadLong(incoming, "limit").intValue(),
                200));

        Map<String, Long> requestedPositions = payloadPositions(incoming);
        if (requestedPositions.isEmpty()) {
            requestedPositions = conversationService.getReadPositions(userId);
        }

        List<ChatMessage> missing = new ArrayList<>();
        Map<String, Long> latestPositions = new LinkedHashMap<>();
        List<String> denied = new ArrayList<>();
        boolean hasMore = false;
        int processed = 0;
        for (Map.Entry<String, Long> entry : requestedPositions.entrySet()) {
            if (processed >= MAX_SYNC_CONVERSATIONS || missing.size() >= MAX_SYNC_MESSAGES) {
                hasMore = true;
                break;
            }
            processed++;
            String conversationId = entry.getKey();
            if (!conversationService.canAccess(conversationId, userId)) {
                denied.add(conversationId);
                continue;
            }
            long after = Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
            int remaining = Math.min(perConversationLimit, MAX_SYNC_MESSAGES - missing.size());
            List<ChatMessage> items = chatMessageService.getMessagesAfter(
                    conversationId, userId, after, remaining);
            missing.addAll(items);
            long latest = conversationService.getLastSequence(conversationId);
            latestPositions.put(conversationId, latest);
            if (!items.isEmpty()
                    && items.get(items.size() - 1).getSequence() != null
                    && items.get(items.size() - 1).getSequence() < latest) {
                hasMore = true;
            }
        }

        missing.sort(Comparator
                .comparing(ChatMessage::getConversationId)
                .thenComparing(message -> message.getSequence() == null ? 0 : message.getSequence()));
        Map<Long, User> senderCache = new HashMap<>();
        List<Map<String, Object>> messagePayloads = missing.stream()
                .map(message -> messagePayload(message, senderCache.computeIfAbsent(
                        message.getFromUserId(), userService::getUserInfo)))
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", messagePayloads);
        payload.put("latestPositions", latestPositions);
        payload.put("deniedConversationIds", denied);
        payload.put("hasMore", hasMore);
        sendEvent(session, envelope("SYNC_RESPONSE", incoming.getRequestId(), null, null, payload));
    }

    private void handleChatRead(WebSocketSession session, WebSocketEnvelope incoming) {
        requireRequestId(incoming);
        String conversationId = requiredConversationId(incoming);
        Long sequence = payloadLong(incoming, "lastReadSequence");
        if (sequence == null || sequence < 0) {
            throw new IllegalArgumentException("已读序列号无效");
        }

        Long userId = getUserId(session);
        long safeSequence = Math.min(sequence, conversationService.getLastSequence(conversationId));
        conversationService.markRead(conversationId, userId, safeSequence);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("lastReadSequence", safeSequence);
        WebSocketEnvelope readEvent = envelope("CHAT_READ", incoming.getRequestId(), null,
                conversationId, payload);
        deliverToConversation(conversationId, readEvent, null);
    }

    private void handleRecall(WebSocketSession session, WebSocketEnvelope incoming) {
        requireRequestId(incoming);
        String messageId = payloadString(incoming, "messageId");
        if (!StringUtils.hasText(messageId)) throw new IllegalArgumentException("消息 ID 不能为空");

        Long userId = getUserId(session);
        ChatMessage original = chatMessageService.getByMessageId(messageId);
        chatMessageService.recallMessage(messageId, userId);
        if (original == null) throw new IllegalArgumentException("消息不存在");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("sequence", original.getSequence());
        WebSocketEnvelope recalled = envelope("CHAT_RECALL", incoming.getRequestId(),
                original.getClientMsgId(), original.getConversationId(), payload);
        deliverMessage(original, recalled);
    }

    private void handleBurn(WebSocketSession session, WebSocketEnvelope incoming) {
        requireRequestId(incoming);
        String messageId = payloadString(incoming, "messageId");
        if (!StringUtils.hasText(messageId)) throw new IllegalArgumentException("消息 ID 不能为空");

        Long userId = getUserId(session);
        ChatMessage original = chatMessageService.getByMessageId(messageId);
        chatMessageService.markAsBurned(messageId, userId);
        if (original == null) throw new IllegalArgumentException("消息不存在");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("sequence", original.getSequence());
        WebSocketEnvelope burned = envelope("CHAT_BURN", incoming.getRequestId(),
                original.getClientMsgId(), original.getConversationId(), payload);
        deliverMessage(original, burned);
    }

    private void handleTyping(WebSocketSession session, WebSocketEnvelope incoming, String event) {
        String conversationId = requiredConversationId(incoming);
        Long userId = getUserId(session);
        if (!conversationService.canSend(conversationId, userId)) return;

        User user = ONLINE_USERS.get(userId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("nickname", user == null ? "" : user.getNickname());
        deliverToConversation(conversationId,
                envelope(event, incoming.getRequestId(), null, conversationId, payload),
                userId);
    }

    private void validateTargetAndPermission(Long senderId, Long toUserId, Long groupId) {
        if ((toUserId == null) == (groupId == null)) {
            throw new IllegalArgumentException("消息接收方无效");
        }
        if (groupId != null) {
            if (!groupService.isMember(groupId, senderId)) {
                throw new IllegalArgumentException("你已不在该群");
            }
            if (groupService.isMuted(groupId, senderId)) {
                throw new IllegalArgumentException("你已被禁言，无法发送消息");
            }
            return;
        }

        if (senderId.equals(toUserId) || userService.getUserInfo(toUserId) == null) {
            throw new IllegalArgumentException("接收用户不存在");
        }
        if (!friendService.isFriend(senderId, toUserId)) {
            throw new IllegalArgumentException("双方非好友关系，消息无法发送");
        }
        if (friendService.isBlockedBy(senderId, toUserId)) {
            throw new IllegalArgumentException("消息发送失败，对方暂不接受消息");
        }
    }

    private void deliverMessage(ChatMessage message, WebSocketEnvelope event) {
        if (message.getGroupId() != null) {
            broadcastToGroup(message.getGroupId(), event, null);
        } else {
            sendToUser(message.getToUserId(), event);
            sendToUser(message.getFromUserId(), event);
        }
    }

    private void deliverToConversation(String conversationId,
                                       WebSocketEnvelope event,
                                       Long excludeUserId) {
        var privateParticipants = ConversationIds.parsePrivate(conversationId);
        if (privateParticipants.isPresent()) {
            long first = privateParticipants.get().firstUserId();
            long second = privateParticipants.get().secondUserId();
            if (excludeUserId == null || excludeUserId != first) sendToUser(first, event);
            if (excludeUserId == null || excludeUserId != second) sendToUser(second, event);
            return;
        }
        ConversationIds.parseGroup(conversationId)
                .ifPresent(groupId -> broadcastToGroup(groupId, event, excludeUserId));
    }

    private Map<String, Object> messagePayload(ChatMessage message, User sender) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", message.getMessageId());
        payload.put("clientMsgId", message.getClientMsgId());
        payload.put("conversationId", message.getConversationId());
        payload.put("sequence", message.getSequence());
        payload.put("fromUserId", message.getFromUserId());
        payload.put("senderDeviceId", message.getSenderDeviceId());
        payload.put("fromNickname", sender == null ? "" : sender.getNickname());
        payload.put("fromAvatar", sender == null ? "" : sender.getAvatar());
        payload.put("toUserId", message.getToUserId());
        payload.put("groupId", message.getGroupId());
        payload.put("type", message.getType());
        payload.put("contentType", message.getType());
        payload.put("content", message.getContent());
        payload.put("replyToId", message.getReplyToId());
        payload.put("mentionUserIds", message.getMentionUserIds());
        payload.put("isBurn", message.getIsBurn());
        payload.put("burnDuration", message.getBurnDuration());
        payload.put("isRecalled", message.getIsRecalled());
        payload.put("status", message.getStatus());
        payload.put("clientCreatedAt", message.getClientCreatedAt() == null
                ? null : message.getClientCreatedAt().toString());
        payload.put("createTime", message.getCreateTime() == null
                ? null : message.getCreateTime().toString());
        return payload;
    }

    private Map<String, Long> payloadPositions(WebSocketEnvelope incoming) {
        Object value = incoming.getPayload() == null ? null : incoming.getPayload().get("positions");
        if (!(value instanceof Map<?, ?> raw)) return Map.of();

        Map<String, Long> positions = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String conversationId)) continue;
            Long sequence = numberToLong(entry.getValue());
            if (sequence != null && sequence >= 0) positions.put(conversationId, sequence);
        }
        return positions;
    }

    private String requiredConversationId(WebSocketEnvelope incoming) {
        String conversationId = incoming.getConversationId();
        if (!StringUtils.hasText(conversationId)) {
            conversationId = payloadString(incoming, "conversationId");
        }
        if (!StringUtils.hasText(conversationId) || conversationId.length() > 64) {
            throw new IllegalArgumentException("会话标识无效");
        }
        return conversationId;
    }

    private void requireRequestId(WebSocketEnvelope incoming) {
        if (!StringUtils.hasText(incoming.getRequestId())
                || !incoming.getRequestId().matches("^[A-Za-z0-9:_-]{6,80}$")) {
            throw new IllegalArgumentException("requestId 无效");
        }
    }

    private LocalDateTime clientCreatedAt(WebSocketEnvelope incoming) {
        Long timestamp = incoming.getTimestamp();
        if (timestamp == null || timestamp <= 0) return LocalDateTime.now();
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        } catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }

    private String payloadString(WebSocketEnvelope incoming, String key) {
        if (incoming.getPayload() == null) return null;
        Object value = incoming.getPayload().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long payloadLong(WebSocketEnvelope incoming, String key) {
        if (incoming.getPayload() == null) return null;
        return numberToLong(incoming.getPayload().get(key));
    }

    private Long numberToLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && text.matches("^-?\\d+$")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean payloadBoolean(WebSocketEnvelope incoming, String key) {
        if (incoming.getPayload() == null) return false;
        Object value = incoming.getPayload().get(key);
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String normalizedEvent(String event) {
        return event == null ? "" : event.trim().toUpperCase(Locale.ROOT);
    }

    private Long getUserId(WebSocketSession session) {
        return attributeLong(session, USER_ID_ATTRIBUTE);
    }

    private Long attributeLong(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private String attributeString(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private WebSocketEnvelope envelope(String event,
                                       String requestId,
                                       String clientMsgId,
                                       String conversationId,
                                       Map<String, Object> payload) {
        WebSocketEnvelope envelope = new WebSocketEnvelope();
        envelope.setVersion(1);
        envelope.setEvent(event);
        envelope.setRequestId(requestId);
        envelope.setClientMsgId(clientMsgId);
        envelope.setConversationId(conversationId);
        envelope.setTimestamp(System.currentTimeMillis());
        envelope.setPayload(payload == null ? new LinkedHashMap<>() : payload);
        return envelope;
    }

    private Map<String, Object> errorPayload(String code, String message, boolean retryable) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("retryable", retryable);
        return payload;
    }

    private void sendError(WebSocketSession session,
                           WebSocketEnvelope incoming,
                           String code,
                           String message,
                           boolean retryable) {
        FAILURE_COUNT.increment();
        sendEvent(session, envelope("ERROR", incoming.getRequestId(), incoming.getClientMsgId(),
                incoming.getConversationId(), errorPayload(code, message, retryable)));
    }

    private void broadcastPresence(String status, Long userId, String nickname) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("userId", userId);
        payload.put("nickname", nickname);
        broadcast(envelope("PRESENCE_CHANGED", null, null, null, payload));
    }

    private void sendOnlineListToAll() {
        ONLINE_SESSIONS.values().forEach(sessions -> sessions.forEach(this::sendOnlineList));
    }

    private void sendOnlineList(WebSocketSession session) {
        List<Map<String, Object>> users = ONLINE_USERS.values().stream()
                .map(user -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", user.getId());
                    item.put("nickname", user.getNickname());
                    item.put("avatar", user.getAvatar());
                    item.put("online", 1);
                    return item;
                })
                .toList();
        sendEvent(session, envelope("ONLINE_LIST", null, null, null, Map.of("users", users)));
    }

    private void broadcast(WebSocketEnvelope event) {
        ONLINE_SESSIONS.values().forEach(sessions ->
                sessions.forEach(session -> sendEvent(session, event)));
    }

    private void broadcastToGroup(Long groupId, WebSocketEnvelope event, Long excludeUserId) {
        List<Map<String, Object>> members = groupService.getGroupMembers(groupId);
        for (Map<String, Object> member : members) {
            Long memberId = numberToLong(member.get("userId"));
            if (memberId == null || (excludeUserId != null && excludeUserId.equals(memberId))) continue;
            sendToUser(memberId, event);
        }
    }

    private void sendToUser(Long userId, WebSocketEnvelope event) {
        if (userId == null) return;
        Set<WebSocketSession> sessions = ONLINE_SESSIONS.get(userId);
        if (sessions != null) sessions.forEach(session -> sendEvent(session, event));
    }

    private void sendEvent(WebSocketSession session, WebSocketEnvelope event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException exception) {
            FAILURE_COUNT.increment();
            log.warn("WebSocket 发送失败: userId={}, error={}", getUserId(session), exception.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (IOException ignored) {
            // 连接已经不可用，无需继续传播关闭异常。
        }
    }

    /** 好友控制器使用的实时刷新通知。 */
    public void sendFriendNotification(Long targetUserId, String content) {
        sendToUser(targetUserId, envelope("FRIEND_CHANGED", null, null, null,
                Map.of("message", content == null ? "好友状态有更新" : content)));
    }

    public static List<User> getOnlineUsers() {
        return new ArrayList<>(ONLINE_USERS.values());
    }

    public static int getOnlineCount() {
        return ONLINE_SESSIONS.size();
    }

    public static int getOnlineConnectionCount() {
        return ONLINE_SESSIONS.values().stream().mapToInt(Set::size).sum();
    }

    public static WebSocketMetrics metricsSnapshot() {
        long count = EVENT_COUNT.sum();
        double averageMs = count == 0 ? 0.0
                : EVENT_PROCESSING_NANOS.sum() / 1_000_000.0 / count;
        return new WebSocketMetrics(
                count,
                ACK_COUNT.sum(),
                FAILURE_COUNT.sum(),
                averageMs,
                List.copyOf(CONNECTION_EVENTS)
        );
    }

    private static void recordConnectionEvent(String event,
                                              WebSocketSession session,
                                              Long userId,
                                              String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("timestamp", Instant.now().toString());
        item.put("event", event);
        if (userId != null) item.put("userId", userId);
        if (session != null && session.getRemoteAddress() != null) {
            String remoteAddress = session.getRemoteAddress().getAddress() == null
                    ? session.getRemoteAddress().getHostString()
                    : session.getRemoteAddress().getAddress().getHostAddress();
            item.put("remoteAddress", remoteAddress.substring(0, Math.min(remoteAddress.length(), 64)));
        }
        if (StringUtils.hasText(reason)) {
            item.put("reason", reason.substring(0, Math.min(reason.length(), 120)));
        }
        CONNECTION_EVENTS.addFirst(Map.copyOf(item));
        while (CONNECTION_EVENTS.size() > MAX_CONNECTION_EVENTS) CONNECTION_EVENTS.pollLast();
    }

    public record WebSocketMetrics(
            long events,
            long acknowledgements,
            long failures,
            double averageProcessingMs,
            List<Map<String, Object>> recentConnections
    ) {
    }
}
