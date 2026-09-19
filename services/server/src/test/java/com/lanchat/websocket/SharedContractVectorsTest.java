package com.lanchat.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.WebSocketEnvelope;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.web.socket.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Shared files exercise the real handler; only persistence, JWT and the transport are test doubles. */
@ResourceLock("chat-websocket-online-sessions")
class SharedContractVectorsTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final JwtUtil jwt = mock(JwtUtil.class);
    final UserService users = mock(UserService.class);
    final ChatMessageService messages = mock(ChatMessageService.class);
    final ConversationService conversations = mock(ConversationService.class);
    final ChatWebSocketHandler handler = new ChatWebSocketHandler(mapper, jwt, messages, conversations,
            mock(FileService.class), users, mock(GroupService.class), mock(FriendService.class),
            mock(FileTransferService.class), mock(BroadcastService.class));
    final List<WebSocketSession> sessions = new ArrayList<>();
    final List<JsonNode> sent = new ArrayList<>();

    @AfterEach void close() throws Exception {
        for (var session : sessions) handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.shutdownExecutor();
    }

    JsonNode vectors() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.isDirectory(root.resolve("contracts"))) root = root.getParent();
        return mapper.readTree(root.resolve("contracts/fixtures/core-v1.json").toFile());
    }
    JsonNode frame(String id) throws Exception {
        for (var v : vectors().get("frames")) if (id.equals(v.path("id").asText())) return v.get("frame");
        throw new AssertionError(id);
    }
    WebSocketSession session() throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        doAnswer(call -> { sent.add(mapper.readTree(((TextMessage) call.getArgument(0)).getPayload())); return null; })
                .when(session).sendMessage(any(TextMessage.class));
        sessions.add(session);
        return session;
    }
    void configureAuth() {
        for (String token : List.of("fixture-access", "fixture-revoked")) {
            when(jwt.isAccessToken(token)).thenReturn(true);
            when(jwt.getUserIdFromToken(token)).thenReturn(7L);
            when(jwt.getDeviceTypeFromToken(token)).thenReturn("desktop");
        }
        User user = new User(); user.setId(7L); user.setStatus(1); user.setNickname("Fixture");
        DeviceLogin device = new DeviceLogin(); device.setId(99L);
        when(users.getUserInfo(7L)).thenReturn(user);
        when(users.getActiveDevice("fixture-access", 7L, "desktop")).thenReturn(device);
        when(users.isAccessTokenActive("fixture-access", 7L, "desktop")).thenReturn(true);
    }
    void send(WebSocketSession session, JsonNode frame) throws Exception {
        handler.handleMessage(session, new TextMessage(frame.toString()));
    }
    JsonNode event(String event) {
        return sent.stream().filter(f -> event.equals(f.path("event").asText())).findFirst().orElseThrow();
    }

    @Test void authenticationVersionAndUnknownEventVectorsMatchActualHandler() throws Exception {
        configureAuth();
        for (var vector : vectors().get("frames")) {
            if (!vector.has("javaEvent")) continue;
            var session = session();
            if (vector.path("id").asText().equals("unknown-client")) send(session, frame("normal-auth"));
            sent.clear();
            send(session, vector.get("frame"));
            JsonNode result = event(vector.get("javaEvent").asText());
            assertEquals(1, result.path("version").asInt(), vector.path("id").asText());
            assertTrue(result.path("timestamp").isIntegralNumber());
            if (vector.has("javaCode")) assertEquals(vector.get("javaCode"), result.at("/payload/code"));
            assertFalse(result.toString().contains("fixture-access"));
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
    }

    @Test void duplicateClientMsgIdReturnsCommittedAckAndReconnectQueriesAfterCursor() throws Exception {
        configureAuth();
        var session = session(); send(session, frame("normal-auth")); sent.clear();
        ChatMessage committed = mapper.treeToValue(frame("event-after-ack").get("payload"), ChatMessage.class);
        when(messages.getByClientMsgId(7L, "client-msg-0001")).thenReturn(committed);
        send(session, frame("duplicate-client-msg-id"));
        send(session, frame("duplicate-client-msg-id"));
        var acks = sent.stream().filter(f -> "CHAT_ACK".equals(f.path("event").asText())).toList();
        assertEquals(2, acks.size());
        for (var ack : acks) {
            assertEquals(42, ack.at("/payload/sequence").asLong());
            assertTrue(ack.at("/payload/duplicated").asBoolean());
            assertEquals("server-msg-0001", ack.at("/payload/messageId").asText());
        }
        // A retry must not create another message or broadcast another delivery.
        assertTrue(sent.stream().noneMatch(f -> "CHAT_DELIVER".equals(f.path("event").asText())));
        when(conversations.getAccessibleConversationIds(7L)).thenReturn(List.of("private:7:8"));
        when(conversations.canAccess("private:7:8", 7L)).thenReturn(true);
        when(conversations.getLastSequence("private:7:8")).thenReturn(42L);
        when(messages.getMessagesAfter("private:7:8", 7L, 41L, 100)).thenReturn(List.of(committed));
        sent.clear(); send(session, frame("reconnect-sync-request"));
        var sync = event("SYNC_RESPONSE");
        assertEquals(42, sync.at("/payload/latestPositions/private:7:8").asLong());
        assertEquals(1, sync.at("/payload/messages").size());
        assertEquals(42, sync.at("/payload/messages/0/sequence").asLong());
        verify(messages).getMessagesAfter("private:7:8", 7L, 41L, 100);
    }

    @Test void generatedJavaEnvelopeKeepsLegacyMissingVersionAndExplicitNullDistinct() throws Exception {
        assertEquals(1, mapper.treeToValue(frame("missing-version-legacy"), WebSocketEnvelope.class).getVersion());
        assertNull(mapper.treeToValue(frame("null-version"), WebSocketEnvelope.class).getVersion());
        var rest = vectors().get("rest");
        for (var v : rest) if (v.path("id").asText().equals("rest-message-integer-flags")) {
            // REST entity fields use integer flags and type, unlike CHAT_SEND's boolean isBurn.
            var message = mapper.treeToValue(v.get("value"), ChatMessage.class);
            assertEquals(1, message.getIsRecalled());
            assertEquals("image", message.getType());
        }
    }
}
