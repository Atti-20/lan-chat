package com.lanchat.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.FileTransferCompletionDTO;
import com.lanchat.dto.FileTransferOfferDTO;
import com.lanchat.dto.FileTransferRoute;
import com.lanchat.dto.FileTransferVO;
import com.lanchat.dto.ReliableMessageResult;
import com.lanchat.entity.ChatMessage;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketFileTransferTest {

    private static final String CONVERSATION_ID = "private:7:9";
    private static final String CLIENT_TRANSFER_ID = "11111111111111111111111111111111";
    private static final String TRANSFER_ID = "22222222222222222222222222222222";
    private static final String FILE_NAME = "project-plan.pdf";
    private static final long FILE_SIZE = 4_096L;
    private static final String FILE_TYPE = "application/pdf";
    private static final String FILE_HASH = "a".repeat(64);
    private static final String SDP = "v=0\r\no=- 1 1 IN IP4 127.0.0.1";

    private ObjectMapper objectMapper;
    private JwtUtil jwtUtil;
    private ChatMessageService chatMessageService;
    private ConversationService conversationService;
    private FileService fileService;
    private UserService userService;
    private FriendService friendService;
    private FileTransferService fileTransferService;
    private BroadcastService broadcastService;
    private ChatWebSocketHandler handler;
    private final List<ClientSession> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jwtUtil = mock(JwtUtil.class);
        chatMessageService = mock(ChatMessageService.class);
        conversationService = mock(ConversationService.class);
        fileService = mock(FileService.class);
        userService = mock(UserService.class);
        friendService = mock(FriendService.class);
        fileTransferService = mock(FileTransferService.class);
        broadcastService = mock(BroadcastService.class);
        when(broadcastService.listPending(any())).thenReturn(List.of());

        handler = new ChatWebSocketHandler(
                objectMapper,
                jwtUtil,
                chatMessageService,
                conversationService,
                fileService,
                userService,
                mock(GroupService.class),
                friendService,
                fileTransferService,
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
    void rejectsUnauthorizedOfferWithoutTrustingForgedTarget() throws Exception {
        ClientSession sender = authenticate(7L, 70L);
        ClientSession forgedTarget = authenticate(10L, 100L);
        clearAllEvents();
        when(conversationService.canSend(CONVERSATION_ID, 7L)).thenReturn(false);

        send(sender, "FILE_TRANSFER_OFFER", "offer_denied", null, CONVERSATION_ID,
                Map.of(
                        "transferId", CLIENT_TRANSFER_ID,
                        "toUserId", 10L,
                        "name", FILE_NAME,
                        "size", FILE_SIZE,
                        "mime", FILE_TYPE,
                        "fileHash", FILE_HASH,
                        "sdp", SDP
                ));

        JsonNode error = onlyEvent(sender, "ERROR");
        assertEquals("BUSINESS_REJECTED", error.path("payload").path("code").asText());
        assertTrue(error.path("payload").path("message").asText().contains("可发送的私聊会话"));
        assertFalse(hasEvent(forgedTarget, "FILE_TRANSFER_OFFER"));
        verify(fileTransferService, never()).createOffer(any(), any(), any());
    }

    @Test
    void createsAuthorizedOfferAndDeliversOnlyToDerivedReceiverSessions() throws Exception {
        ClientSession sender = authenticate(7L, 70L);
        ClientSession receiverDeviceOne = authenticate(9L, 91L);
        ClientSession receiverDeviceTwo = authenticate(9L, 92L);
        ClientSession forgedTarget = authenticate(10L, 100L);
        clearAllEvents();

        when(conversationService.canSend(CONVERSATION_ID, 7L)).thenReturn(true);
        when(fileTransferService.createOffer(eq(7L), eq(70L), any(FileTransferOfferDTO.class)))
                .thenReturn(transfer("OFFERED", null, "PEER_TO_PEER"));

        send(sender, "FILE_TRANSFER_OFFER", "offer_allowed", null, CONVERSATION_ID,
                Map.of(
                        "transferId", CLIENT_TRANSFER_ID,
                        "toUserId", 10L,
                        "name", FILE_NAME,
                        "size", FILE_SIZE,
                        "mime", FILE_TYPE,
                        "fileHash", FILE_HASH,
                        "sdp", SDP
                ));

        ArgumentCaptor<FileTransferOfferDTO> request = ArgumentCaptor.forClass(FileTransferOfferDTO.class);
        verify(fileTransferService).createOffer(eq(7L), eq(70L), request.capture());
        assertEquals(CLIENT_TRANSFER_ID, request.getValue().clientTransferId());
        assertEquals(CONVERSATION_ID, request.getValue().conversationId());
        assertEquals(FILE_NAME, request.getValue().fileName());
        assertEquals(FILE_SIZE, request.getValue().fileSize());
        assertEquals(FILE_TYPE, request.getValue().fileType());
        assertEquals(FILE_HASH, request.getValue().fileHash());

        JsonNode ready = onlyEvent(sender, "FILE_TRANSFER_READY");
        assertEquals(TRANSFER_ID, ready.path("payload").path("transferId").asText());
        assertEquals(CLIENT_TRANSFER_ID, ready.path("payload").path("clientTransferId").asText());

        JsonNode firstOffer = onlyEvent(receiverDeviceOne, "FILE_TRANSFER_OFFER");
        JsonNode secondOffer = onlyEvent(receiverDeviceTwo, "FILE_TRANSFER_OFFER");
        assertEquals(7L, firstOffer.path("payload").path("fromUserId").asLong());
        assertEquals(TRANSFER_ID, firstOffer.path("payload").path("transferId").asText());
        assertEquals(firstOffer, secondOffer);
        assertFalse(hasEvent(sender, "FILE_TRANSFER_OFFER"));
        assertFalse(hasEvent(forgedTarget, "FILE_TRANSFER_OFFER"));
    }

    @Test
    void firstAnswerClaimsReceiverAndTargetsOnlyOriginatingSenderDevice() throws Exception {
        ClientSession senderDeviceOne = authenticate(7L, 70L);
        ClientSession senderDeviceTwo = authenticate(7L, 71L);
        ClientSession receiverDeviceOne = authenticate(9L, 91L);
        ClientSession receiverDeviceTwo = authenticate(9L, 92L);
        clearAllEvents();

        FileTransferVO claimed = transfer("CLAIMED", 91L, "PEER_TO_PEER");
        when(fileTransferService.claimReceiverDevice(TRANSFER_ID, 9L, 91L)).thenReturn(claimed);
        when(fileTransferService.markNegotiating(TRANSFER_ID, 7L, 70L)).thenReturn(claimed);
        when(fileTransferService.authorizePeerSignal(TRANSFER_ID, 9L, 91L))
                .thenReturn(new FileTransferRoute(
                        TRANSFER_ID, CONVERSATION_ID, 7L, 70L, "NEGOTIATING"));

        send(receiverDeviceOne, "FILE_TRANSFER_ANSWER", "answer_first", null, CONVERSATION_ID,
                Map.of("transferId", TRANSFER_ID, "sdp", SDP));

        JsonNode answer = onlyEvent(senderDeviceOne, "FILE_TRANSFER_ANSWER");
        assertEquals(SDP, answer.path("payload").path("sdp").asText());
        assertEquals(91L, answer.path("payload").path("receiverDeviceId").asLong());
        assertFalse(hasEvent(senderDeviceTwo, "FILE_TRANSFER_ANSWER"));
        assertFalse(hasEvent(receiverDeviceOne, "FILE_TRANSFER_ANSWER"));

        when(fileTransferService.claimReceiverDevice(TRANSFER_ID, 9L, 92L))
                .thenThrow(new IllegalArgumentException("传输任务已由其他接收设备认领"));
        send(receiverDeviceTwo, "FILE_TRANSFER_ANSWER", "answer_second", null, CONVERSATION_ID,
                Map.of("transferId", TRANSFER_ID, "sdp", SDP));

        assertEquals(1, events(senderDeviceOne, "FILE_TRANSFER_ANSWER").size());
        JsonNode canceled = onlyEvent(receiverDeviceTwo, "FILE_TRANSFER_CANCELED");
        assertTrue(canceled.path("payload").path("message").asText().contains("其他接收设备"));
        verify(fileTransferService).claimReceiverDevice(TRANSFER_ID, 9L, 91L);
        verify(fileTransferService).claimReceiverDevice(TRANSFER_ID, 9L, 92L);
        verify(fileTransferService, times(1)).markNegotiating(TRANSFER_ID, 7L, 70L);
        verify(fileTransferService, times(1)).authorizePeerSignal(TRANSFER_ID, 9L, 91L);
    }

    @Test
    void completionPassesReceiverIntegrityReportAndTargetsSenderDevice() throws Exception {
        ClientSession senderDeviceOne = authenticate(7L, 70L);
        ClientSession senderDeviceTwo = authenticate(7L, 71L);
        ClientSession receiver = authenticate(9L, 91L);
        clearAllEvents();

        FileTransferRoute senderRoute = new FileTransferRoute(
                TRANSFER_ID, CONVERSATION_ID, 7L, 70L, "TRANSFERRING");
        FileTransferVO completed = transfer("COMPLETED", 91L, "PEER_TO_PEER");
        when(fileTransferService.authorizePeerSignal(TRANSFER_ID, 9L, 91L)).thenReturn(senderRoute);
        when(fileTransferService.completePeerToPeer(
                eq(TRANSFER_ID), eq(9L), eq(91L), any(FileTransferCompletionDTO.class)))
                .thenReturn(completed);

        send(receiver, "FILE_TRANSFER_COMPLETE", "complete_file", null, CONVERSATION_ID,
                Map.of(
                        "transferId", TRANSFER_ID,
                        "fileHash", FILE_HASH,
                        "fileSize", FILE_SIZE
                ));

        ArgumentCaptor<FileTransferCompletionDTO> integrity =
                ArgumentCaptor.forClass(FileTransferCompletionDTO.class);
        InOrder completionOrder = inOrder(fileTransferService);
        completionOrder.verify(fileTransferService)
                .authorizePeerSignal(TRANSFER_ID, 9L, 91L);
        completionOrder.verify(fileTransferService)
                .completePeerToPeer(eq(TRANSFER_ID), eq(9L), eq(91L), integrity.capture());
        assertEquals(FILE_HASH, integrity.getValue().fileHash());
        assertEquals(FILE_SIZE, integrity.getValue().fileSize());

        JsonNode complete = onlyEvent(senderDeviceOne, "FILE_TRANSFER_COMPLETE");
        assertEquals(FILE_HASH, complete.path("payload").path("fileHash").asText());
        assertEquals(FILE_SIZE, complete.path("payload").path("fileSize").asLong());
        assertFalse(hasEvent(senderDeviceTwo, "FILE_TRANSFER_COMPLETE"));
        assertFalse(hasEvent(receiver, "FILE_TRANSFER_COMPLETE"));
    }

    @Test
    void peerToPeerChatSendRequiresCompletedAttachmentBeforeSaving() throws Exception {
        ClientSession sender = authenticate(7L, 70L);
        authenticate(9L, 91L);
        clearAllEvents();

        when(friendService.isFriend(7L, 9L)).thenReturn(true);
        when(friendService.isBlockedBy(7L, 9L)).thenReturn(false);
        FileTransferVO completed = transfer("COMPLETED", 91L, "PEER_TO_PEER");
        when(fileTransferService.requireCompletedAttachment(
                TRANSFER_ID, CONVERSATION_ID, 7L, 70L)).thenReturn(completed);
        when(chatMessageService.saveReliableMessage(any(ChatMessage.class), eq(CONVERSATION_ID)))
                .thenAnswer(invocation -> {
                    ChatMessage message = invocation.getArgument(0);
                    message.setMessageId("message_12345678");
                    message.setConversationId(CONVERSATION_ID);
                    message.setSequence(12L);
                    return new ReliableMessageResult(message, false);
                });

        String content = objectMapper.writeValueAsString(Map.of(
                "transferPath", "PEER_TO_PEER",
                "transferId", TRANSFER_ID,
                "name", FILE_NAME,
                "size", FILE_SIZE,
                "mime", FILE_TYPE,
                "fileHash", FILE_HASH
        ));
        send(sender, "CHAT_SEND", "chat_direct", "client_msg_123456", CONVERSATION_ID,
                Map.of(
                        "toUserId", 9L,
                        "contentType", "file",
                        "content", content
                ));

        ArgumentCaptor<ChatMessage> savedMessage = ArgumentCaptor.forClass(ChatMessage.class);
        InOrder validationOrder = inOrder(fileTransferService, chatMessageService);
        validationOrder.verify(fileTransferService).requireCompletedAttachment(
                TRANSFER_ID, CONVERSATION_ID, 7L, 70L);
        validationOrder.verify(chatMessageService)
                .saveReliableMessage(savedMessage.capture(), eq(CONVERSATION_ID));
        assertNull(savedMessage.getValue().getFilePath());
        assertEquals(TRANSFER_ID,
                objectMapper.readTree(savedMessage.getValue().getContent()).path("transferId").asText());
        verify(fileService, never()).canAccessFile(any(), any());
        assertEquals(1, events(sender, "CHAT_ACK").size());
    }

    private ClientSession authenticate(long userId, long deviceId) throws Exception {
        String token = "access-token-" + userId + "-" + deviceId;
        when(jwtUtil.isAccessToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(userId);
        when(jwtUtil.getDeviceTypeFromToken(token)).thenReturn("web");
        when(userService.isAccessTokenActive(token, userId, "web")).thenReturn(true);

        User user = new User();
        user.setId(userId);
        user.setNickname("user-" + userId);
        user.setStatus(1);
        when(userService.getUserInfo(userId)).thenReturn(user);

        DeviceLogin device = new DeviceLogin();
        device.setId(deviceId);
        when(userService.getActiveDevice(token, userId, "web")).thenReturn(device);

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
        send(client, "AUTH", "auth_" + userId + "_" + deviceId, null, null,
                Map.of("token", token));
        assertTrue(hasEvent(client, "AUTH_OK"));
        client.sent().clear();
        return client;
    }

    private void send(ClientSession client,
                      String event,
                      String requestId,
                      String clientMsgId,
                      String conversationId,
                      Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", 1);
        envelope.put("event", event);
        envelope.put("requestId", requestId);
        envelope.put("timestamp", System.currentTimeMillis());
        if (clientMsgId != null) envelope.put("clientMsgId", clientMsgId);
        if (conversationId != null) envelope.put("conversationId", conversationId);
        envelope.put("payload", payload);
        handler.handleMessage(client.session(),
                new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    private void clearAllEvents() {
        clients.forEach(client -> client.sent().clear());
    }

    private boolean hasEvent(ClientSession client, String eventName) {
        return !events(client, eventName).isEmpty();
    }

    private JsonNode onlyEvent(ClientSession client, String eventName) {
        List<JsonNode> matching = events(client, eventName);
        assertEquals(1, matching.size(), () -> "expected one " + eventName + " event, got " + matching);
        return matching.get(0);
    }

    private List<JsonNode> events(ClientSession client, String eventName) {
        return client.sent().stream()
                .map(TextMessage::getPayload)
                .map(this::readTree)
                .filter(event -> eventName.equals(event.path("event").asText()))
                .toList();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private FileTransferVO transfer(String status, Long receiverDeviceId, String transportPath) {
        return new FileTransferVO(
                TRANSFER_ID,
                CLIENT_TRANSFER_ID,
                CONVERSATION_ID,
                7L,
                70L,
                9L,
                receiverDeviceId,
                FILE_NAME,
                FILE_SIZE,
                FILE_TYPE,
                FILE_HASH,
                status,
                transportPath,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private record ClientSession(WebSocketSession session, List<TextMessage> sent) {
    }
}
