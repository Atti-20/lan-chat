package com.lanchat.websocket;

import cn.hutool.json.JSONUtil;
import com.lanchat.dto.WebSocketMessage;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.User;
import com.lanchat.service.*;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 核心处理器
 * 支持消息类型：chat / recall / burn / read / typing / screenshot / system / online-list
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    /** 在线用户 session 映射：userId -> session */
    private static final Map<Long, WebSocketSession> ONLINE_SESSIONS = new ConcurrentHashMap<>();

    /** 在线用户信息：userId -> User */
    private static final Map<Long, User> ONLINE_USERS = new ConcurrentHashMap<>();

    /** 阅后即焚不允许的消息类型 */
    private static final Set<String> BURN_FORBIDDEN_TYPES = Set.of("file", "voice");

    @Resource
    private ChatMessageService chatMessageService;

    @Resource
    private UserService userService;

    @Resource
    private GroupService groupService;

    @Resource
    private FriendService friendService;

    // ==================== 连接生命周期 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = getUserIdFromSession(session);
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        ONLINE_SESSIONS.put(userId, session);

        User user = userService.getUserInfo(userId);
        if (user != null) {
            user.setOnline(1);
            ONLINE_USERS.put(userId, user);
            userService.updateOnlineStatus(userId, 1);
        }

        log.info("用户 {} 上线，当前在线人数: {}", userId, ONLINE_SESSIONS.size());

        broadcastSystemMessage("online", userId);
        sendOnlineListToAll();
        sendOnlineList(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        WebSocketMessage wsMsg = JSONUtil.toBean(payload, WebSocketMessage.class);

        Long fromUserId = getUserIdFromSession(session);
        if (fromUserId == null) return;

        User fromUser = ONLINE_USERS.get(fromUserId);

        switch (wsMsg.getType() == null ? "" : wsMsg.getType()) {
            case "chat" -> handleChatMessage(wsMsg, fromUser);
            case "recall" -> handleRecallMessage(wsMsg, fromUser);
            case "burn" -> handleBurnMessage(wsMsg);
            case "read" -> handleReadReceipt(wsMsg, fromUser);
            case "typing" -> handleTyping(wsMsg, fromUser);
            case "screenshot" -> handleScreenshot(wsMsg, fromUser);
            default -> log.warn("未知消息类型: {}", wsMsg.getType());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long userId = getUserIdFromSession(session);
        if (userId == null) return;

        ONLINE_SESSIONS.remove(userId);
        ONLINE_USERS.remove(userId);
        userService.updateOnlineStatus(userId, 0);

        log.info("用户 {} 下线，当前在线人数: {}", userId, ONLINE_SESSIONS.size());

        broadcastSystemMessage("offline", userId);
        sendOnlineListToAll();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket 传输错误: userId={}, error={}", getUserIdFromSession(session), exception.getMessage());
        if (session.isOpen()) {
            session.close();
        }
    }

    // ==================== 消息处理 ====================

    /**
     * 处理聊天消息
     * 校验规则：
     * - 私聊：双方必须为好友关系，且未被对方拉黑
     * - 群聊：发送者必须是群成员，且未被禁言
     * - 阅后即焚：file/voice 类型不允许焚毁
     */
    private void handleChatMessage(WebSocketMessage wsMsg, User fromUser) {
        // 阅后即焚类型限制
        if (Boolean.TRUE.equals(wsMsg.getIsBurn()) && wsMsg.getContentType() != null
                && BURN_FORBIDDEN_TYPES.contains(wsMsg.getContentType())) {
            sendErrorToUser(fromUser.getId(), "文件和语音消息不支持阅后即焚");
            return;
        }

        // 群聊消息校验
        if (wsMsg.getGroupId() != null) {
            // 检查是否为群成员
            if (!groupService.isMember(wsMsg.getGroupId(), fromUser.getId())) {
                sendErrorToUser(fromUser.getId(), "你已不在该群");
                return;
            }
            // 检查是否被禁言
            if (groupService.isMuted(wsMsg.getGroupId(), fromUser.getId())) {
                sendErrorToUser(fromUser.getId(), "你已被禁言，无法发送消息");
                return;
            }
        } else if (wsMsg.getToUserId() != null) {
            // 私聊消息校验：双方必须为好友
            if (!friendService.isFriend(fromUser.getId(), wsMsg.getToUserId())) {
                sendErrorToUser(fromUser.getId(), "双方非好友关系，消息无法发送");
                return;
            }
            // 检查是否被对方拉黑
            if (friendService.isBlockedBy(fromUser.getId(), wsMsg.getToUserId())) {
                sendErrorToUser(fromUser.getId(), "消息发送失败，对方暂不接受消息");
                return;
            }
        }

        // 生成消息唯一ID
        if (wsMsg.getMessageId() == null || wsMsg.getMessageId().isEmpty()) {
            wsMsg.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        }
        wsMsg.setFromUserId(fromUser.getId());
        wsMsg.setFromNickname(fromUser.getNickname());
        wsMsg.setFromAvatar(fromUser.getAvatar());
        wsMsg.setTimestamp(LocalDateTime.now());

        // 持久化到数据库
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessageId(wsMsg.getMessageId());
        chatMessage.setFromUserId(fromUser.getId());
        chatMessage.setToUserId(wsMsg.getToUserId());
        chatMessage.setGroupId(wsMsg.getGroupId());
        chatMessage.setType(wsMsg.getContentType());
        chatMessage.setContent(wsMsg.getContent());
        chatMessage.setReplyToId(wsMsg.getReplyToId());
        chatMessage.setMentionUserIds(wsMsg.getMentionUserIds());
        chatMessage.setIsBurn(wsMsg.getIsBurn() != null && wsMsg.getIsBurn() ? 1 : 0);
        chatMessage.setBurnDuration(wsMsg.getBurnDuration() != null ? wsMsg.getBurnDuration() : 5);
        chatMessage.setIsRecalled(0);
        chatMessage.setStatus(0);
        chatMessage.setCreateTime(LocalDateTime.now());
        chatMessageService.save(chatMessage);

        String json = JSONUtil.toJsonStr(wsMsg);

        if (wsMsg.getGroupId() != null) {
            // 群聊消息：广播给群内所有在线成员（包括发送者，用于多端同步）
            broadcastToGroup(wsMsg.getGroupId(), json, null);
        } else if (wsMsg.getToUserId() != null) {
            // 私聊消息：发给接收者 + 自己（多端同步）
            sendToUser(wsMsg.getToUserId(), json);
            sendToUser(fromUser.getId(), json);
        }
    }

    /**
     * 处理消息撤回
     * 规则：仅发送者本人可撤回，2分钟内有效
     * 阅后即焚消息未被阅读前可撤回，已阅读则无法撤回
     */
    private void handleRecallMessage(WebSocketMessage wsMsg, User fromUser) {
        try {
            // 检查焚毁消息是否已被阅读
            ChatMessage original = chatMessageService.getByMessageId(wsMsg.getMessageId());
            if (original != null && original.getIsBurn() == 1 && original.getStatus() == 2) {
                sendErrorToUser(fromUser.getId(), "阅后即焚消息已被阅读，无法撤回");
                return;
            }

            chatMessageService.recallMessage(wsMsg.getMessageId(), fromUser.getId());

            WebSocketMessage recallMsg = new WebSocketMessage();
            recallMsg.setType("recall");
            recallMsg.setMessageId(wsMsg.getMessageId());
            recallMsg.setFromUserId(fromUser.getId());
            recallMsg.setFromNickname(fromUser.getNickname());
            recallMsg.setTimestamp(LocalDateTime.now());

            String json = JSONUtil.toJsonStr(recallMsg);

            if (original != null) {
                if (original.getGroupId() != null) {
                    broadcastToGroup(original.getGroupId(), json, null);
                } else {
                    sendToUser(original.getToUserId(), json);
                    sendToUser(original.getFromUserId(), json);
                }
            }
        } catch (Exception e) {
            log.error("撤回消息失败: {}", e.getMessage());
            sendErrorToUser(fromUser.getId(), "撤回失败: " + e.getMessage());
        }
    }

    /**
     * 处理阅后即焚消息
     * 多端同步：一端触发焚毁，其他端同步更新为占位符
     */
    private void handleBurnMessage(WebSocketMessage wsMsg) {
        chatMessageService.markAsBurned(wsMsg.getMessageId());

        WebSocketMessage burnMsg = new WebSocketMessage();
        burnMsg.setType("burn");
        burnMsg.setMessageId(wsMsg.getMessageId());
        burnMsg.setTimestamp(LocalDateTime.now());

        String json = JSONUtil.toJsonStr(burnMsg);

        ChatMessage original = chatMessageService.getByMessageId(wsMsg.getMessageId());
        if (original != null) {
            if (original.getGroupId() != null) {
                broadcastToGroup(original.getGroupId(), json, null);
            } else {
                sendToUser(original.getToUserId(), json);
                sendToUser(original.getFromUserId(), json);
            }
        }
    }

    /**
     * 处理已读回执
     * 多端同步：一端已读，其他端同步消除红点
     */
    private void handleReadReceipt(WebSocketMessage wsMsg, User fromUser) {
        // 标记消息为已读
        if (wsMsg.getToUserId() != null) {
            chatMessageService.markAsRead(wsMsg.getToUserId(), fromUser.getId());
        }

        // 广播已读状态给发送方的所有在线设备
        WebSocketMessage readMsg = new WebSocketMessage();
        readMsg.setType("read");
        readMsg.setFromUserId(fromUser.getId());
        readMsg.setToUserId(wsMsg.getToUserId());
        readMsg.setTimestamp(LocalDateTime.now());

        String json = JSONUtil.toJsonStr(readMsg);
        // 通知消息发送方（多端同步已读状态）
        sendToUser(wsMsg.getToUserId(), json);
    }

    /**
     * 处理正在输入提示
     */
    private void handleTyping(WebSocketMessage wsMsg, User fromUser) {
        wsMsg.setFromUserId(fromUser.getId());
        wsMsg.setFromNickname(fromUser.getNickname());
        wsMsg.setTimestamp(LocalDateTime.now());

        String json = JSONUtil.toJsonStr(wsMsg);

        if (wsMsg.getGroupId() != null) {
            broadcastToGroup(wsMsg.getGroupId(), json, fromUser.getId());
        } else if (wsMsg.getToUserId() != null) {
            sendToUser(wsMsg.getToUserId(), json);
        }
    }

    /**
     * 处理截屏检测（阅后即焚消息）
     * App端检测到截屏行为时，通知发送方"对方可能已截屏"
     */
    private void handleScreenshot(WebSocketMessage wsMsg, User fromUser) {
        ChatMessage original = chatMessageService.getByMessageId(wsMsg.getMessageId());
        if (original == null || original.getIsBurn() != 1) return;

        // 向消息发送方发送截屏提醒
        WebSocketMessage screenshotMsg = new WebSocketMessage();
        screenshotMsg.setType("screenshot");
        screenshotMsg.setMessageId(wsMsg.getMessageId());
        screenshotMsg.setContent("对方可能已截屏");
        screenshotMsg.setFromUserId(fromUser.getId());
        screenshotMsg.setTimestamp(LocalDateTime.now());

        sendToUser(original.getFromUserId(), JSONUtil.toJsonStr(screenshotMsg));
    }

    // ==================== 工具方法 ====================

    private Long getUserIdFromSession(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if ("userId".equals(pair[0]) && pair.length > 1) {
                return Long.parseLong(pair[1]);
            }
        }
        return null;
    }

    /**
     * 发送错误消息给用户
     */
    private void sendErrorToUser(Long userId, String errorMsg) {
        WebSocketMessage msg = new WebSocketMessage();
        msg.setType("error");
        msg.setContent(errorMsg);
        msg.setTimestamp(LocalDateTime.now());
        sendToUser(userId, JSONUtil.toJsonStr(msg));
    }

    /**
     * 广播系统消息（上线/下线通知）
     */
    private void broadcastSystemMessage(String action, Long userId) {
        User user = ONLINE_USERS.get(userId);
        String nickname = user != null ? user.getNickname() : "";

        WebSocketMessage sysMsg = new WebSocketMessage();
        sysMsg.setType("system");
        sysMsg.setContent(action.equals("online") ? nickname + " 上线了" : nickname + " 下线了");
        sysMsg.setFromUserId(userId);
        sysMsg.setFromNickname(nickname);
        sysMsg.setTimestamp(LocalDateTime.now());

        broadcast(JSONUtil.toJsonStr(sysMsg));
    }

    private void sendOnlineListToAll() {
        ONLINE_SESSIONS.values().forEach(this::sendOnlineList);
    }

    private void sendOnlineList(WebSocketSession session) {
        List<User> users = new ArrayList<>(ONLINE_USERS.values());
        WebSocketMessage listMsg = new WebSocketMessage();
        listMsg.setType("online-list");
        listMsg.setContent(JSONUtil.toJsonStr(users));
        listMsg.setTimestamp(LocalDateTime.now());
        sendToSession(session, JSONUtil.toJsonStr(listMsg));
    }

    private void broadcast(String message) {
        ONLINE_SESSIONS.values().forEach(session -> sendToSession(session, message));
    }

    private void broadcastToGroup(Long groupId, String message, Long excludeUserId) {
        List<Map<String, Object>> members = groupService.getGroupMembers(groupId);
        for (Map<String, Object> member : members) {
            Long memberId = (Long) member.get("userId");
            if (excludeUserId != null && excludeUserId.equals(memberId)) continue;
            sendToUser(memberId, message);
        }
    }

    private void sendToUser(Long userId, String message) {
        WebSocketSession session = ONLINE_SESSIONS.get(userId);
        if (session != null && session.isOpen()) {
            sendToSession(session, message);
        }
    }

    private void sendToSession(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            }
        } catch (IOException e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }

    public static List<User> getOnlineUsers() {
        return new ArrayList<>(ONLINE_USERS.values());
    }

    public static int getOnlineCount() {
        return ONLINE_SESSIONS.size();
    }
}
