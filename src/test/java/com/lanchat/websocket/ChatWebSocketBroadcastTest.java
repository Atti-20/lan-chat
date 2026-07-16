package com.lanchat.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.BroadcastService;
import com.lanchat.service.ChatMessageService;
import com.lanchat.service.ConversationService;
import com.lanchat.service.FileService;
import com.lanchat.service.FileTransferService;
import com.lanchat.service.FriendService;
import com.lanchat.service.GroupService;
import com.lanchat.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatWebSocketBroadcastTest {

    private ObjectMapper objectMapper;
    private JwtUtil jwtUtil;
    private UserService userService;
    private BroadcastService broadcastService;
    private ChatWebSocketHandler handler;
    private final List<ClientSession> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jwtUtil = mock(JwtUtil.class);
        userService = mock(UserService.class);
        broadcastService = mock(BroadcastService.class);
        when(broadcastService.listPending(any())).thenReturn(List.of());
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
                broadcastService
        );
    }

    @AfterEach
    void tearDown() {
        for (ClientSession client : clients) {
            handler.afterConnectionClosed(client.session(), CloseStatus.NORMAL);
        }
        handler.shutdownExecutor();
    }

    @Test
    void cancellationOnlyNotifiesReceiversCreatorAndAdministrators() throws Exception {
        ClientSession admin = authenticate(1L, "admin");
        ClientSession creator = authenticate(7L, "alice");
        ClientSession receiver = authenticate(8L, "bob");
        ClientSession unrelated = authenticate(9L, "carol");
        clients.forEach(client -> client.messages().clear());

        Broadcast cancelled = new Broadcast();
        cancelled.setId(31L);
        cancelled.setSenderId(7L);
        cancelled.setStatus("CANCELLED");
        when(broadcastService.getReceiverIds(31L)).thenReturn(List.of(8L));

        handler.notifyBroadcastCancelled(cancelled);

        assertEquals("CANCELLED", onlyEvent(admin, "BROADCAST_UPDATED")
                .path("payload").path("status").asText());
        assertTrue(hasEvent(creator, "BROADCAST_UPDATED"));
        assertTrue(hasEvent(receiver, "BROADCAST_UPDATED"));
        assertFalse(hasEvent(unrelated, "BROADCAST_UPDATED"));
    }

    @Test
    void forbiddenBroadcastConfirmationIsNotReportedAsRetryableServerFailure() throws Exception {
        ClientSession client = authenticate(8L, "bob");
        client.messages().clear();
        doThrow(new AccessDeniedException("当前用户不是该广播接收者"))
                .when(broadcastService)
                .confirm(any(), any(), any(), any());

        String request = """
                {"version":1,"event":"BROADCAST_ACK","requestId":"ack_123456","timestamp":1,
                 "payload":{"broadcastId":31,"status":"RECEIVED"}}
                """;
        handler.handleMessage(client.session(), new TextMessage(request));

        JsonNode error = onlyEvent(client, "ERROR");
        assertEquals("FORBIDDEN", error.path("payload").path("code").asText());
        assertFalse(error.path("payload").path("retryable").asBoolean(true));
    }

    private ClientSession authenticate(Long userId, String username) throws Exception {
        String token = "token-" + userId;
        when(jwtUtil.isAccessToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(userId);
        when(jwtUtil.getDeviceTypeFromToken(token)).thenReturn("web");

        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setNickname(username);
        user.setStatus(1);
        when(userService.getUserInfo(userId)).thenReturn(user);

        DeviceLogin device = new DeviceLogin();
        device.setId(userId * 10);
        when(userService.getActiveDevice(token, userId, "web")).thenReturn(device);
        when(userService.isAccessTokenActive(token, userId, "web")).thenReturn(true);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        List<TextMessage> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        ClientSession client = new ClientSession(session, sent);
        clients.add(client);
        String auth = """
                {"version":1,"event":"AUTH","requestId":"auth_%d","timestamp":1,
                 "payload":{"token":"%s"}}
                """.formatted(userId, token);
        handler.handleMessage(session, new TextMessage(auth));
        return client;
    }

    private boolean hasEvent(ClientSession client, String eventName) {
        return client.messages().stream()
                .map(TextMessage::getPayload)
                .map(this::readTree)
                .anyMatch(event -> eventName.equals(event.path("event").asText()));
    }

    private JsonNode onlyEvent(ClientSession client, String eventName) {
        return client.messages().stream()
                .map(TextMessage::getPayload)
                .map(this::readTree)
                .filter(event -> eventName.equals(event.path("event").asText()))
                .findFirst()
                .orElseThrow();
    }

    private JsonNode readTree(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record ClientSession(WebSocketSession session, List<TextMessage> messages) {
    }
}
