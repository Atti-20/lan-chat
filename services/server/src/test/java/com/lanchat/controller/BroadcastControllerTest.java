package com.lanchat.controller;

import com.lanchat.dto.BroadcastConfirmDTO;
import com.lanchat.dto.BroadcastCreateDTO;
import com.lanchat.dto.BroadcastDetailDTO;
import com.lanchat.dto.BroadcastStatsDTO;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;
import com.lanchat.security.LoginUser;
import com.lanchat.service.BroadcastService;
import com.lanchat.websocket.ChatWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BroadcastControllerTest {

    private BroadcastService broadcastService;
    private ChatWebSocketHandler webSocketHandler;
    private BroadcastController controller;

    @BeforeEach
    void setUp() {
        broadcastService = mock(BroadcastService.class);
        webSocketHandler = mock(ChatWebSocketHandler.class);
        controller = new BroadcastController(broadcastService);
        ReflectionTestUtils.setField(controller, "webSocketHandler", webSocketHandler);
        authenticate(7L, "alice", "desktop");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAlwaysUsesAuthenticatedSender() {
        BroadcastCreateDTO request = new BroadcastCreateDTO();
        Broadcast created = new Broadcast();
        created.setId(31L);
        when(broadcastService.create(7L, request)).thenReturn(created);

        var result = controller.create(request);

        assertEquals(200, result.getCode());
        assertEquals(created, result.getData());
        verify(broadcastService).create(7L, request);
    }

    @Test
    void pendingUsesAuthenticatedRecipient() {
        Broadcast pending = new Broadcast();
        pending.setId(31L);
        when(broadcastService.listPending(7L)).thenReturn(List.of(pending));

        var result = controller.pending();

        assertEquals(List.of(pending), result.getData());
        verify(broadcastService).listPending(7L);
    }

    @Test
    void detailAndViewUseAuthenticatedRecipient() {
        Broadcast broadcast = new Broadcast();
        broadcast.setId(31L);
        BroadcastReceiver receiver = new BroadcastReceiver();
        receiver.setBroadcastId(31L);
        receiver.setUserId(7L);
        BroadcastDetailDTO detail = new BroadcastDetailDTO(broadcast, receiver, List.of(), false);
        when(broadcastService.getDetail(31L, 7L)).thenReturn(detail);
        when(broadcastService.markViewed(31L, 7L)).thenReturn(receiver);

        assertEquals(detail, controller.detail(31L).getData());
        assertEquals(receiver, controller.view(31L).getData());
        verify(broadcastService).getDetail(31L, 7L);
        verify(broadcastService).markViewed(31L, 7L);
    }

    @Test
    void confirmationUsesAuthenticatedUserAndDeviceType() {
        BroadcastConfirmDTO request = new BroadcastConfirmDTO();
        request.setStatus("RECEIVED");
        BroadcastReceiver confirmed = new BroadcastReceiver();
        confirmed.setBroadcastId(31L);
        confirmed.setUserId(7L);
        confirmed.setConfirmStatus("RECEIVED");
        when(broadcastService.confirm(31L, 7L, "desktop", request)).thenReturn(confirmed);

        var result = controller.confirm(31L, request);

        assertEquals(200, result.getCode());
        assertEquals(confirmed, result.getData());
        verify(broadcastService).confirm(31L, 7L, "desktop", request);
    }

    @Test
    void statsUsesAuthenticatedRequester() {
        BroadcastStatsDTO stats = new BroadcastStatsDTO(
                31L, 3, 2, 2, 1, 2, List.of(8L, 9L), 0, false, Map.of("RECEIVED", 1L));
        when(broadcastService.getStats(31L, 7L)).thenReturn(stats);

        assertEquals(stats, controller.stats(31L).getData());

        verify(broadcastService).getStats(31L, 7L);
    }

    @Test
    void administratorCancelUsesAuthenticatedOperatorAndPublishesUpdate() {
        authenticate(1L, "admin", "web");
        Broadcast cancelled = new Broadcast();
        cancelled.setId(31L);
        cancelled.setStatus("CANCELLED");
        when(broadcastService.cancel(31L, 1L)).thenReturn(cancelled);

        var result = controller.cancel(31L);

        assertEquals(200, result.getCode());
        assertEquals(cancelled, result.getData());
        verify(broadcastService).cancel(31L, 1L);
        verify(webSocketHandler).notifyBroadcastCancelled(cancelled);
    }

    @Test
    void anonymousDirectInvocationIsRejected() {
        SecurityContextHolder.clearContext();

        assertThrows(AccessDeniedException.class, controller::list);
    }

    private void authenticate(Long userId, String username, String deviceType) {
        LoginUser loginUser = new LoginUser(userId, username, deviceType, "access-token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }
}
