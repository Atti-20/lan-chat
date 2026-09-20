package com.lanchat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.common.DeviceSessionsRevokedEvent;
import com.lanchat.common.ConversationReadChangedEvent;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.ChatMessageService;
import com.lanchat.service.ConversationService;
import com.lanchat.service.FileService;
import com.lanchat.service.FriendService;
import com.lanchat.service.FileTransferService;
import com.lanchat.service.BroadcastService;
import com.lanchat.service.GroupService;
import com.lanchat.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock("chat-websocket-online-sessions")
class ChatWebSocketHandlerTest {

    private ChatWebSocketHandler handler;

    @AfterEach
    void tearDown() {
        if (handler != null) handler.shutdownExecutor();
    }

    @Test
    void authenticatesDesktopDeviceAfterHandshakeAndReturnsSanitizedAuthEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserService userService = mock(UserService.class);
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ConversationService conversationService = mock(ConversationService.class);
        handler = new ChatWebSocketHandler(
                objectMapper,
                jwtUtil,
                chatMessageService,
                conversationService,
                mock(FileService.class),
                userService,
                mock(GroupService.class),
                mock(FriendService.class),
                mock(FileTransferService.class),
                mock(BroadcastService.class)
        );

        String token = "access-token-not-in-url";
        when(jwtUtil.isAccessToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(7L);
        when(jwtUtil.getDeviceTypeFromToken(token)).thenReturn("desktop");
        when(userService.isAccessTokenActive(token, 7L, "desktop")).thenReturn(true);

        User user = new User();
        user.setId(7L);
        user.setNickname("Alice");
        user.setPassword("must-never-be-sent");
        user.setStatus(1);
        when(userService.getUserInfo(7L)).thenReturn(user);

        DeviceLogin device = new DeviceLogin();
        device.setId(99L);
        when(userService.getActiveDevice(token, 7L, "desktop")).thenReturn(device);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        List<TextMessage> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        String auth = """
                {"version":1,"event":"AUTH","requestId":"req_123456","timestamp":1,
                 "payload":{"token":"access-token-not-in-url"}}
                """;
        handler.handleMessage(session, new TextMessage(auth));

        var events = sent.stream()
                .map(TextMessage::getPayload)
                .map(payload -> {
                    try {
                        return objectMapper.readTree(payload);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .toList();
        var authOk = events.stream()
                .filter(event -> "AUTH_OK".equals(event.get("event").asText()))
                .findFirst()
                .orElseThrow();

        assertEquals(7L, authOk.get("payload").get("userId").asLong());
        assertEquals(99L, authOk.get("payload").get("deviceId").asLong());
        assertFalse(authOk.toString().contains(token));
        assertTrue(events.stream().noneMatch(event -> event.toString().contains("must-never-be-sent")));

        when(conversationService.getAccessibleConversationIds(7L))
                .thenReturn(List.of("private:7:8"));
        when(conversationService.canAccess("private:7:8", 7L)).thenReturn(true);
        when(conversationService.getLastSequence("private:7:8")).thenReturn(12L);
        when(chatMessageService.getMessagesAfter("private:7:8", 7L, 0L, 100))
                .thenReturn(List.of());
        handler.handleMessage(session, new TextMessage("""
                {"version":1,"event":"SYNC_REQUEST","requestId":"req_sync_123",
                 "timestamp":1,"payload":{"positions":{},"limit":100}}
                """));
        var sync = sent.stream()
                .map(TextMessage::getPayload)
                .map(payload -> {
                    try {
                        return objectMapper.readTree(payload);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .filter(event -> "SYNC_RESPONSE".equals(event.get("event").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals(12L, sync.get("payload").get("latestPositions")
                .get("private:7:8").asLong());
        verify(chatMessageService).getMessagesAfter("private:7:8", 7L, 0L, 100);

        String mobileToken = "mobile-access-token-not-in-url";
        when(jwtUtil.isAccessToken(mobileToken)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(mobileToken)).thenReturn(7L);
        when(jwtUtil.getDeviceTypeFromToken(mobileToken)).thenReturn("android");
        when(userService.isAccessTokenActive(mobileToken, 7L, "android")).thenReturn(true);
        DeviceLogin mobileDevice = new DeviceLogin();
        mobileDevice.setId(100L);
        when(userService.getActiveDevice(mobileToken, 7L, "android")).thenReturn(mobileDevice);
        WebSocketSession mobileSession = mock(WebSocketSession.class);
        when(mobileSession.getAttributes()).thenReturn(new HashMap<>());
        when(mobileSession.isOpen()).thenReturn(true);
        List<TextMessage> mobileSent = new ArrayList<>();
        doAnswer(invocation -> {
            mobileSent.add(invocation.getArgument(0));
            return null;
        }).when(mobileSession).sendMessage(any(TextMessage.class));
        handler.handleMessage(mobileSession, new TextMessage("""
                {"version":1,"event":"AUTH","requestId":"req_mobile_123","timestamp":1,
                 "payload":{"token":"mobile-access-token-not-in-url"}}
                """));

        handler.notifyConversationRead(new ConversationReadChangedEvent(
                "private:7:8", 7L, 12L, 10L, 2L));
        var readChanged = sent.stream()
                .map(TextMessage::getPayload)
                .map(payload -> {
                    try {
                        return objectMapper.readTree(payload);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .filter(event -> "CHAT_READ".equals(event.get("event").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals(10L, readChanged.get("payload").get("lastReadSequence").asLong());
        assertEquals(2L, readChanged.get("payload").get("unreadCount").asLong());
        assertTrue(mobileSent.stream()
                .map(TextMessage::getPayload)
                .map(payload -> {
                    try {
                        return objectMapper.readTree(payload);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .anyMatch(event -> "CHAT_READ".equals(event.get("event").asText())
                        && event.get("payload").get("unreadCount").asLong() == 2L));

        handler.forceLogoutDevices(new DeviceSessionsRevokedEvent(
                7L, List.of(99L), "SESSION_REPLACED", "同类型设备已在其他位置登录"));

        var forceLogout = sent.stream()
                .map(TextMessage::getPayload)
                .map(payload -> {
                    try {
                        return objectMapper.readTree(payload);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .filter(event -> "FORCE_LOGOUT".equals(event.get("event").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals("SESSION_REPLACED", forceLogout.get("payload").get("reason").asText());
        verify(session).close(CloseStatus.POLICY_VIOLATION);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.afterConnectionClosed(mobileSession, CloseStatus.NORMAL);
    }

    @Test
    void expandsAdminAllMentionIntoTheCurrentMemberSnapshot() {
        GroupService groupService = mock(GroupService.class);
        handler = new ChatWebSocketHandler(
                new ObjectMapper(),
                mock(JwtUtil.class),
                mock(ChatMessageService.class),
                mock(ConversationService.class),
                mock(FileService.class),
                mock(UserService.class),
                groupService,
                mock(FriendService.class),
                mock(FileTransferService.class),
                mock(BroadcastService.class)
        );
        when(groupService.getMemberRole(12L, 7L)).thenReturn(1);
        when(groupService.getGroupMembers(12L)).thenReturn(List.of(
                Map.of("userId", 7L),
                Map.of("userId", 19L),
                Map.of("userId", 11L)
        ));

        String normalized = ReflectionTestUtils.invokeMethod(
                handler, "normalizeMentionTargets", "ALL", 12L, 7L);

        assertEquals("11,19", normalized);
    }

    @Test
    void rejectsANormalMemberWhoTriesToRecreateAtAllWithNumericTargets() {
        GroupService groupService = mock(GroupService.class);
        handler = newMentionHandler(groupService);
        when(groupService.getMemberRole(12L, 7L)).thenReturn(0);
        when(groupService.getGroupMembers(12L)).thenReturn(List.of(
                Map.of("userId", 7L),
                Map.of("userId", 19L),
                Map.of("userId", 11L)
        ));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        handler, "normalizeMentionTargets", "11,19", 12L, 7L));

        assertEquals("只有群主或管理员可以@所有人", error.getMessage());
    }

    @Test
    void permitsANormalMemberToMentionTheOnlyOtherMemberDirectly() {
        GroupService groupService = mock(GroupService.class);
        handler = newMentionHandler(groupService);
        when(groupService.getGroupMembers(12L)).thenReturn(List.of(
                Map.of("userId", 7L),
                Map.of("userId", 11L)
        ));

        String normalized = ReflectionTestUtils.invokeMethod(
                handler, "normalizeMentionTargets", "11", 12L, 7L);

        assertEquals("11", normalized);
    }

    private ChatWebSocketHandler newMentionHandler(GroupService groupService) {
        return new ChatWebSocketHandler(
                new ObjectMapper(),
                mock(JwtUtil.class),
                mock(ChatMessageService.class),
                mock(ConversationService.class),
                mock(FileService.class),
                mock(UserService.class),
                groupService,
                mock(FriendService.class),
                mock(FileTransferService.class),
                mock(BroadcastService.class)
        );
    }

    @Test
    void freezesTwoHundredLongMentionTargetsWithoutExceedingThePersistedSnapshotColumn() {
        GroupService groupService = mock(GroupService.class);
        handler = new ChatWebSocketHandler(
                new ObjectMapper(),
                mock(JwtUtil.class),
                mock(ChatMessageService.class),
                mock(ConversationService.class),
                mock(FileService.class),
                mock(UserService.class),
                groupService,
                mock(FriendService.class),
                mock(FileTransferService.class),
                mock(BroadcastService.class)
        );
        when(groupService.getMemberRole(12L, 7L)).thenReturn(1);
        List<Map<String, Object>> members = new ArrayList<>();
        members.add(Map.of("userId", 7L));
        for (long offset = 0; offset < 200; offset += 1) {
            members.add(Map.of("userId", Long.MAX_VALUE - offset));
        }
        when(groupService.getGroupMembers(12L)).thenReturn(members);

        String normalized = ReflectionTestUtils.invokeMethod(
                handler, "normalizeMentionTargets", "ALL", 12L, 7L);

        assertEquals(200, normalized.split(",").length);
        assertTrue(normalized.length() <= 4096, normalized.length() + " exceeds VARCHAR(4096)");
    }

    @Test
    void technicalNotificationAccountCannotAuthenticateWithAnExistingDeviceToken() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserService userService = mock(UserService.class);
        handler = new ChatWebSocketHandler(
                objectMapper,
                jwtUtil,
                mock(ChatMessageService.class),
                mock(ConversationService.class),
                mock(FileService.class),
                userService,
                mock(GroupService.class),
                mock(FriendService.class),
                mock(FileTransferService.class),
                mock(BroadcastService.class)
        );
        String token = "legacy-notification-access";
        when(jwtUtil.isAccessToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(7L);
        when(jwtUtil.getDeviceTypeFromToken(token)).thenReturn("desktop");
        User notificationAccount = new User();
        notificationAccount.setId(7L);
        notificationAccount.setUsername("broadcast-notify");
        notificationAccount.setSignature("系统广播通知账户");
        notificationAccount.setStatus(1);
        when(userService.getUserInfo(7L)).thenReturn(notificationAccount);
        DeviceLogin device = new DeviceLogin();
        device.setId(99L);
        when(userService.getActiveDevice(token, 7L, "desktop")).thenReturn(device);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        List<TextMessage> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        handler.handleMessage(session, new TextMessage("""
                {"version":1,"event":"AUTH","requestId":"notification_auth","timestamp":1,
                 "payload":{"token":"legacy-notification-access"}}
                """));

        assertTrue(sent.stream().map(TextMessage::getPayload)
                .anyMatch(payload -> payload.contains("\"event\":\"FORCE_LOGOUT\"")));
        assertTrue(sent.stream().map(TextMessage::getPayload)
                .noneMatch(payload -> payload.contains("\"event\":\"AUTH_OK\"")));
        verify(userService, never()).updateOnlineStatus(7L, 1);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void rejectsDeviceReplacedBetweenInitialLookupAndSocketRegistration() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserService userService = mock(UserService.class);
        handler = new ChatWebSocketHandler(
                objectMapper,
                jwtUtil,
                mock(ChatMessageService.class),
                mock(ConversationService.class),
                mock(FileService.class),
                userService,
                mock(GroupService.class),
                mock(FriendService.class),
                mock(FileTransferService.class),
                mock(BroadcastService.class)
        );

        String token = "replaced-during-auth";
        when(jwtUtil.isAccessToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(7L);
        when(jwtUtil.getDeviceTypeFromToken(token)).thenReturn("desktop");

        User user = new User();
        user.setId(7L);
        user.setNickname("Alice");
        user.setStatus(1);
        when(userService.getUserInfo(7L)).thenReturn(user);

        DeviceLogin initiallyActive = new DeviceLogin();
        initiallyActive.setId(99L);
        when(userService.getActiveDevice(token, 7L, "desktop"))
                .thenReturn(initiallyActive)
                .thenReturn(null);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("race-session");
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        List<TextMessage> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        handler.handleMessage(session, new TextMessage("""
                {"version":1,"event":"AUTH","requestId":"req_race_123","timestamp":1,
                 "payload":{"token":"replaced-during-auth"}}
                """));

        var events = sent.stream()
                .map(TextMessage::getPayload)
                .map(payload -> {
                    try {
                        return objectMapper.readTree(payload);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .toList();
        assertTrue(events.stream().anyMatch(event ->
                "FORCE_LOGOUT".equals(event.get("event").asText())));
        assertTrue(events.stream().noneMatch(event ->
                "AUTH_OK".equals(event.get("event").asText())));
        assertEquals(0, ChatWebSocketHandler.getOnlineCount());
        verify(userService, times(2)).getActiveDevice(token, 7L, "desktop");
        verify(userService, never()).updateOnlineStatus(7L, 1);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }
}
