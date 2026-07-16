package com.lanchat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.cluster.ClusterPresenceService;
import com.lanchat.cluster.RealtimeRouter;
import com.lanchat.dto.BroadcastDetailDTO;
import com.lanchat.dto.FileTransferOfferDTO;
import com.lanchat.dto.FileTransferVO;
import com.lanchat.dto.WebSocketEnvelope;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;
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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketClusterRoutingTest {

    private static final long LOCAL_USER_ID = 7_007L;
    private static final long LOCAL_DEVICE_ID = 70_070L;
    private static final long REMOTE_USER_ID = 9_009L;
    private static final String CONVERSATION_ID = "private:7007:9009";
    private static final String CLIENT_TRANSFER_ID = "1".repeat(32);
    private static final String TRANSFER_ID = "2".repeat(32);

    private ObjectMapper objectMapper;
    private JwtUtil jwtUtil;
    private ConversationService conversationService;
    private UserService userService;
    private FileTransferService fileTransferService;
    private BroadcastService broadcastService;
    private RealtimeRouter realtimeRouter;
    private ClusterPresenceService presenceService;
    private ChatWebSocketHandler handler;
    private final List<WebSocketSession> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jwtUtil = mock(JwtUtil.class);
        conversationService = mock(ConversationService.class);
        userService = mock(UserService.class);
        fileTransferService = mock(FileTransferService.class);
        broadcastService = mock(BroadcastService.class);
        realtimeRouter = mock(RealtimeRouter.class);
        presenceService = mock(ClusterPresenceService.class);
        when(broadcastService.listPending(any())).thenReturn(List.of());
        doAnswer(invocation -> Set.copyOf(invocation.getArgument(0)))
                .when(presenceService).getOnlineUserIds(any());

        handler = new ChatWebSocketHandler(
                objectMapper,
                jwtUtil,
                mock(ChatMessageService.class),
                conversationService,
                mock(FileService.class),
                userService,
                mock(GroupService.class),
                mock(FriendService.class),
                fileTransferService,
                broadcastService,
                realtimeRouter,
                presenceService
        );
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(session -> handler.afterConnectionClosed(session, CloseStatus.NORMAL));
        handler.shutdownExecutor();
    }

    @Test
    void authenticatedClientReceivesOnlineListBuiltFromGlobalPresence() throws Exception {
        User remoteUser = activeUser(REMOTE_USER_ID);
        when(userService.getUserInfo(REMOTE_USER_ID)).thenReturn(remoteUser);
        doReturn(Set.of(LOCAL_USER_ID, REMOTE_USER_ID))
                .when(presenceService).getOnlineUserIds(any());

        authenticateLocalUser();

        ArgumentCaptor<WebSocketEnvelope> events = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(realtimeRouter, org.mockito.Mockito.atLeastOnce()).broadcast(events.capture());
        WebSocketEnvelope onlineList = events.getAllValues().stream()
                .filter(event -> "ONLINE_LIST".equals(event.getEvent()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users =
                (List<Map<String, Object>>) onlineList.getPayload().get("users");
        assertEquals(Set.of(LOCAL_USER_ID, REMOTE_USER_ID), users.stream()
                .map(user -> ((Number) user.get("id")).longValue())
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void webRtcOfferUsesGlobalPresenceAndRoutesToRemoteInstance() throws Exception {
        WebSocketSession sender = authenticateLocalUser();
        clearInvocations(realtimeRouter);
        when(conversationService.canSend(CONVERSATION_ID, LOCAL_USER_ID)).thenReturn(true);
        when(presenceService.isUserOnline(REMOTE_USER_ID, false)).thenReturn(true);
        when(fileTransferService.createOffer(
                eq(LOCAL_USER_ID), eq(LOCAL_DEVICE_ID), any(FileTransferOfferDTO.class)))
                .thenReturn(transfer());

        send(sender, """
                {"version":1,"event":"FILE_TRANSFER_OFFER","requestId":"offer_cluster_1",
                 "conversationId":"private:7007:9009","timestamp":1,"payload":{
                   "transferId":"11111111111111111111111111111111",
                   "name":"plan.pdf","size":4096,"mime":"application/pdf",
                   "fileHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "sdp":"v=0\\r\\no=- 1 1 IN IP4 127.0.0.1"}}
                """);

        ArgumentCaptor<WebSocketEnvelope> event = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(realtimeRouter).sendToUser(eq(REMOTE_USER_ID), event.capture());
        assertEquals("FILE_TRANSFER_OFFER", event.getValue().getEvent());
        verify(fileTransferService, never()).fallbackToNodeRelay(any(), any(), any(), any());
    }

    @Test
    void broadcastPublishingRoutesToGloballyOnlineRemoteReceiver() {
        Broadcast broadcast = new Broadcast();
        broadcast.setId(31L);
        BroadcastReceiver receiver = new BroadcastReceiver();
        receiver.setBroadcastId(31L);
        receiver.setUserId(REMOTE_USER_ID);
        when(broadcastService.getReceiverIds(31L)).thenReturn(List.of(REMOTE_USER_ID));
        doReturn(Set.of(REMOTE_USER_ID))
                .when(presenceService).getOnlineUserIds(any());
        when(broadcastService.listPending(REMOTE_USER_ID)).thenReturn(List.of(broadcast));
        when(broadcastService.getDetail(31L, REMOTE_USER_ID))
                .thenReturn(new BroadcastDetailDTO(broadcast, receiver, List.of(), false));

        handler.publishBroadcast(31L);

        ArgumentCaptor<WebSocketEnvelope> event = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        ArgumentCaptor<Runnable> receipt = ArgumentCaptor.forClass(Runnable.class);
        verify(realtimeRouter).sendToUserWithReceipt(
                eq(REMOTE_USER_ID), event.capture(), receipt.capture());
        assertEquals("BROADCAST", event.getValue().getEvent());
        verify(broadcastService, never()).markDelivered(31L, REMOTE_USER_ID);

        receipt.getValue().run();

        verify(broadcastService).markDelivered(31L, REMOTE_USER_ID);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void expiredRemoteLeaseRepairsDatabaseAndBroadcastsOfflineOnce() {
        when(presenceService.isUserDefinitelyOffline(REMOTE_USER_ID, false)).thenReturn(true);
        when(userService.getUserInfo(REMOTE_USER_ID)).thenReturn(activeUser(REMOTE_USER_ID));
        ArgumentCaptor<Consumer<Set<Long>>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(presenceService).bindGlobalOfflineListener(listener.capture());

        listener.getValue().accept(Set.of(REMOTE_USER_ID));

        verify(userService).updateOnlineStatus(REMOTE_USER_ID, 0);
        ArgumentCaptor<WebSocketEnvelope> events = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(realtimeRouter, times(2)).broadcast(events.capture());
        assertTrue(events.getAllValues().stream().anyMatch(event ->
                "PRESENCE_CHANGED".equals(event.getEvent())
                        && "offline".equals(event.getPayload().get("status"))
                        && REMOTE_USER_ID
                                == ((Number) event.getPayload().get("userId")).longValue()));
        assertTrue(events.getAllValues().stream()
                .anyMatch(event -> "ONLINE_LIST".equals(event.getEvent())));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reconcilerNeverMarksUserOfflineWhenALeaseHasReappeared() {
        when(presenceService.isUserDefinitelyOffline(REMOTE_USER_ID, false)).thenReturn(false);
        ArgumentCaptor<Consumer<Set<Long>>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(presenceService).bindGlobalOfflineListener(listener.capture());

        listener.getValue().accept(Set.of(REMOTE_USER_ID));

        verify(userService, never()).updateOnlineStatus(REMOTE_USER_ID, 0);
        verify(realtimeRouter, never()).broadcast(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reconnectRacingDatabaseRepairRestoresOnlineFlagWithoutOfflineEvent() {
        when(presenceService.isUserDefinitelyOffline(REMOTE_USER_ID, false))
                .thenReturn(true, false);
        when(userService.getUserInfo(REMOTE_USER_ID)).thenReturn(activeUser(REMOTE_USER_ID));
        ArgumentCaptor<Consumer<Set<Long>>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(presenceService).bindGlobalOfflineListener(listener.capture());

        listener.getValue().accept(Set.of(REMOTE_USER_ID));

        verify(userService).updateOnlineStatus(REMOTE_USER_ID, 0);
        verify(userService).updateOnlineStatus(REMOTE_USER_ID, 1);
        verify(realtimeRouter, never()).broadcast(any());
    }

    private WebSocketSession authenticateLocalUser() throws Exception {
        String token = "access-token-cluster";
        when(jwtUtil.isAccessToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(LOCAL_USER_ID);
        when(jwtUtil.getDeviceTypeFromToken(token)).thenReturn("web");
        when(userService.getUserInfo(LOCAL_USER_ID)).thenReturn(activeUser(LOCAL_USER_ID));
        DeviceLogin device = new DeviceLogin();
        device.setId(LOCAL_DEVICE_ID);
        when(userService.getActiveDevice(token, LOCAL_USER_ID, "web")).thenReturn(device);
        when(userService.isAccessTokenActive(token, LOCAL_USER_ID, "web")).thenReturn(true);
        when(presenceService.register(
                LOCAL_USER_ID, LOCAL_DEVICE_ID, "session-cluster", true)).thenReturn(true);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-cluster");
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        List<TextMessage> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        sessions.add(session);

        send(session, """
                {"version":1,"event":"AUTH","requestId":"auth_cluster_1","timestamp":1,
                 "payload":{"token":"access-token-cluster"}}
                """);
        assertTrue(sent.stream().map(TextMessage::getPayload)
                .anyMatch(payload -> payload.contains("\"event\":\"AUTH_OK\"")));
        return session;
    }

    private User activeUser(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setNickname("user-" + userId);
        user.setStatus(1);
        return user;
    }

    private FileTransferVO transfer() {
        return new FileTransferVO(
                TRANSFER_ID,
                CLIENT_TRANSFER_ID,
                CONVERSATION_ID,
                LOCAL_USER_ID,
                LOCAL_DEVICE_ID,
                REMOTE_USER_ID,
                null,
                "plan.pdf",
                4_096L,
                "application/pdf",
                "a".repeat(64),
                "OFFERED",
                "PEER_TO_PEER",
                null, null, null, null, null, null, null, null
        );
    }

    private void send(WebSocketSession session, String json) throws Exception {
        handler.handleMessage(session, new TextMessage(json));
    }
}
