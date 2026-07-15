package com.lanchat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.ChatMessageService;
import com.lanchat.service.ConversationService;
import com.lanchat.service.FileService;
import com.lanchat.service.FriendService;
import com.lanchat.service.GroupService;
import com.lanchat.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatWebSocketHandlerTest {

    private ChatWebSocketHandler handler;

    @AfterEach
    void tearDown() {
        if (handler != null) handler.shutdownExecutor();
    }

    @Test
    void authenticatesAfterHandshakeAndReturnsSanitizedAuthEvent() throws Exception {
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
                mock(FriendService.class)
        );

        String token = "access-token-not-in-url";
        when(jwtUtil.isAccessToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(7L);
        when(jwtUtil.getDeviceTypeFromToken(token)).thenReturn("web");

        User user = new User();
        user.setId(7L);
        user.setNickname("Alice");
        user.setPassword("must-never-be-sent");
        user.setStatus(1);
        when(userService.getUserInfo(7L)).thenReturn(user);

        DeviceLogin device = new DeviceLogin();
        device.setId(99L);
        when(userService.getActiveDevice(token, 7L, "web")).thenReturn(device);

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

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    }
}
