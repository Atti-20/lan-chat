package com.lanchat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.BroadcastConfirmDTO;
import com.lanchat.dto.BroadcastCreateDTO;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;
import com.lanchat.entity.User;
import com.lanchat.mapper.BroadcastMapper;
import com.lanchat.mapper.BroadcastReceiverMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.impl.BroadcastServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BroadcastServiceImplTest {

    private BroadcastMapper broadcastMapper;
    private BroadcastReceiverMapper receiverMapper;
    private UserMapper userMapper;
    private FriendService friendService;
    private BroadcastServiceImpl service;

    @BeforeEach
    void setUp() {
        broadcastMapper = mock(BroadcastMapper.class);
        receiverMapper = mock(BroadcastReceiverMapper.class);
        userMapper = mock(UserMapper.class);
        friendService = mock(FriendService.class);
        service = new BroadcastServiceImpl(
                broadcastMapper,
                receiverMapper,
                userMapper,
                friendService,
                new ObjectMapper()
        );
    }

    @Test
    void accountWithoutBroadcastPermissionCannotCreate() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "alice"));
        BroadcastCreateDTO request = validRequest("USERS");
        request.setReceiverIds(List.of(8L));

        assertThrows(AccessDeniedException.class, () -> service.create(7L, request));

        verify(friendService, never()).isFriend(any(), any());
        verify(broadcastMapper, never()).insert(any(Broadcast.class));
    }

    @Test
    void authorizedRegularUserCannotBroadcastToEveryone() {
        when(userMapper.selectById(7L)).thenReturn(authorizedUser(7L, "alice"));

        assertThrows(AccessDeniedException.class,
                () -> service.create(7L, validRequest("ALL")));

        verify(broadcastMapper, never()).insert(any(Broadcast.class));
    }

    @Test
    void authorizedRegularUserCanBroadcastToUnblockedFriend() {
        User bob = activeUser(8L, "bob");
        when(userMapper.selectById(7L)).thenReturn(authorizedUser(7L, "alice"));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(bob));
        when(friendService.isFriend(7L, 8L)).thenReturn(true);
        when(friendService.isBlockedBy(7L, 8L)).thenReturn(false);
        when(broadcastMapper.insert(any(Broadcast.class))).thenAnswer(invocation -> {
            Broadcast broadcast = invocation.getArgument(0);
            broadcast.setId(44L);
            return 1;
        });
        when(receiverMapper.insert(any(BroadcastReceiver.class))).thenReturn(1);
        BroadcastCreateDTO request = validRequest("USERS");
        request.setReceiverIds(List.of(8L, 8L));
        request.setConfirmationRequired(true);

        Broadcast created = service.create(7L, request);

        assertEquals(44L, created.getId());
        assertEquals("USERS", created.getScopeType());
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(receiverMapper).insert(receiverCaptor.capture());
        assertEquals(8L, receiverCaptor.getValue().getUserId());
        assertEquals("PENDING", receiverCaptor.getValue().getConfirmStatus());
    }

    @Test
    void authorizedRegularUserCannotBroadcastToNonFriend() {
        when(userMapper.selectById(7L)).thenReturn(authorizedUser(7L, "alice"));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(activeUser(8L, "bob")));
        when(friendService.isFriend(7L, 8L)).thenReturn(false);
        BroadcastCreateDTO request = validRequest("USERS");
        request.setReceiverIds(List.of(8L));

        assertThrows(AccessDeniedException.class, () -> service.create(7L, request));

        verify(broadcastMapper, never()).insert(any(Broadcast.class));
    }

    @Test
    void authorizedRegularUserCannotBroadcastToBlockedFriend() {
        when(userMapper.selectById(7L)).thenReturn(authorizedUser(7L, "alice"));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(activeUser(8L, "bob")));
        when(friendService.isFriend(7L, 8L)).thenReturn(true);
        when(friendService.isBlockedBy(7L, 8L)).thenReturn(true);
        BroadcastCreateDTO request = validRequest("USERS");
        request.setReceiverIds(List.of(8L));

        assertThrows(AccessDeniedException.class, () -> service.create(7L, request));

        verify(broadcastMapper, never()).insert(any(Broadcast.class));
    }

    @Test
    void nodeAdministratorCanBroadcastToAllActiveRegularUsers() {
        User admin = activeUser(1L, "admin");
        User bob = activeUser(8L, "bob");
        User disabled = activeUser(9L, "disabled");
        disabled.setStatus(0);
        when(userMapper.selectById(1L)).thenReturn(admin);
        // 即使 mock 忽略查询条件，服务也会防御性排除管理员和停用账号。
        when(userMapper.selectList(any())).thenReturn(List.of(admin, bob, disabled));
        when(broadcastMapper.insert(any(Broadcast.class))).thenAnswer(invocation -> {
            Broadcast value = invocation.getArgument(0);
            value.setId(44L);
            return 1;
        });
        when(receiverMapper.insert(any(BroadcastReceiver.class))).thenReturn(1);

        Broadcast created = service.create(1L, validRequest("ALL"));

        assertEquals(44L, created.getId());
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(receiverMapper).insert(receiverCaptor.capture());
        assertEquals(8L, receiverCaptor.getValue().getUserId());
        assertEquals("NOT_REQUIRED", receiverCaptor.getValue().getConfirmStatus());
    }

    @Test
    void groupScopeIsRejectedUntilTeamFeatureExists() {
        when(userMapper.selectById(7L)).thenReturn(authorizedUser(7L, "alice"));
        BroadcastCreateDTO request = validRequest("GROUP");
        request.setGroupId(12L);

        assertThrows(IllegalArgumentException.class, () -> service.create(7L, request));

        verify(broadcastMapper, never()).insert(any(Broadcast.class));
    }

    @Test
    void repeatingSameConfirmationDoesNotWriteAgain() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        BroadcastReceiver confirmed = receiver(21L, 8L, "EXECUTED");
        confirmed.setConfirmedAt(LocalDateTime.now());
        when(broadcastMapper.selectByIdForUpdate(21L)).thenReturn(broadcast);
        when(userMapper.selectById(8L)).thenReturn(activeUser(8L, "bob"));
        when(receiverMapper.selectReceiver(21L, 8L)).thenReturn(confirmed);
        BroadcastConfirmDTO request = confirmation("executed");

        BroadcastReceiver result = service.confirm(21L, 8L, "web", request);

        assertEquals(confirmed, result);
        verify(receiverMapper, never()).confirmIfPending(any(), any(), any(), any());
    }

    @Test
    void pendingAndNotRequiredAreReservedConfirmationValues() {
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L, "admin"));
        BroadcastCreateDTO request = validRequest("ALL");
        request.setConfirmationRequired(true);
        request.setConfirmationOptions(List.of("PENDING"));

        assertThrows(IllegalArgumentException.class, () -> service.create(1L, request));

        verify(broadcastMapper, never()).insert(any(Broadcast.class));
    }

    @Test
    void firstConfirmationAtomicallyWinsAndMarksRecipientHandled() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        BroadcastReceiver pending = receiver(21L, 8L, "PENDING");
        BroadcastReceiver confirmed = receiver(21L, 8L, "NEED_SUPPORT");
        confirmed.setDeliveredAt(LocalDateTime.now());
        confirmed.setViewedAt(LocalDateTime.now());
        confirmed.setConfirmedAt(LocalDateTime.now());
        when(broadcastMapper.selectByIdForUpdate(21L)).thenReturn(broadcast);
        when(userMapper.selectById(8L)).thenReturn(activeUser(8L, "bob"));
        when(receiverMapper.selectReceiver(21L, 8L)).thenReturn(pending, confirmed);
        when(receiverMapper.confirmIfPending(21L, 8L, "NEED_SUPPORT", "desktop")).thenReturn(1);

        BroadcastReceiver result = service.confirm(
                21L, 8L, "desktop", confirmation("need_support"));

        assertEquals("NEED_SUPPORT", result.getConfirmStatus());
        verify(receiverMapper).confirmIfPending(21L, 8L, "NEED_SUPPORT", "desktop");
    }

    @Test
    void concurrentDifferentConfirmationIsRejected() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        BroadcastReceiver pending = receiver(21L, 8L, "PENDING");
        BroadcastReceiver otherWinner = receiver(21L, 8L, "RECEIVED");
        otherWinner.setConfirmedAt(LocalDateTime.now());
        when(broadcastMapper.selectByIdForUpdate(21L)).thenReturn(broadcast);
        when(userMapper.selectById(8L)).thenReturn(activeUser(8L, "bob"));
        when(receiverMapper.selectReceiver(21L, 8L)).thenReturn(pending, otherWinner);
        when(receiverMapper.confirmIfPending(21L, 8L, "EXECUTED", "web")).thenReturn(0);

        assertThrows(IllegalArgumentException.class,
                () -> service.confirm(21L, 8L, "web", confirmation("EXECUTED")));
    }

    @Test
    void administratorPendingListIsAlwaysEmpty() {
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L, "admin"));

        assertTrue(service.listPending(1L).isEmpty());

        verify(broadcastMapper, never()).selectPending(1L);
    }

    @Test
    void administratorDetailDoesNotContainReceipt() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        broadcast.setSenderId(7L);
        when(broadcastMapper.selectById(21L)).thenReturn(broadcast);
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L, "admin"));

        var detail = service.getDetail(21L, 1L);

        assertNull(detail.receiver());
        assertFalse(detail.createdByCurrentUser());
        verify(receiverMapper, never()).selectReceiver(21L, 1L);
    }

    @Test
    void administratorCannotConfirmBroadcast() {
        when(broadcastMapper.selectByIdForUpdate(21L))
                .thenReturn(confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10)));
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L, "admin"));

        assertThrows(AccessDeniedException.class,
                () -> service.confirm(21L, 1L, "web", confirmation("RECEIVED")));

        verify(receiverMapper, never()).selectReceiver(21L, 1L);
    }

    @Test
    void offlinePendingQueryIsScopedToAuthenticatedUser() {
        User bob = activeUser(8L, "bob");
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        when(userMapper.selectById(8L)).thenReturn(bob);
        when(broadcastMapper.selectPending(8L)).thenReturn(List.of(broadcast));

        assertEquals(List.of(broadcast), service.listPending(8L));

        verify(broadcastMapper).selectPending(8L);
    }

    @Test
    void senderCanReadAggregatedConfirmationStats() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        broadcast.setSenderId(7L);
        BroadcastReceiver received = receiver(21L, 8L, "RECEIVED");
        received.setDeliveredAt(LocalDateTime.now());
        received.setViewedAt(LocalDateTime.now());
        received.setConfirmedAt(LocalDateTime.now());
        BroadcastReceiver viewed = receiver(21L, 9L, "PENDING");
        viewed.setDeliveredAt(LocalDateTime.now());
        viewed.setViewedAt(LocalDateTime.now());
        BroadcastReceiver untouched = receiver(21L, 10L, "PENDING");
        when(broadcastMapper.selectById(21L)).thenReturn(broadcast);
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "alice"));
        when(receiverMapper.selectByBroadcastId(21L))
                .thenReturn(List.of(received, viewed, untouched));

        var stats = service.getStats(21L, 7L);

        assertEquals(3, stats.targetCount());
        assertEquals(2, stats.deliveredCount());
        assertEquals(2, stats.viewedCount());
        assertEquals(1, stats.confirmedCount());
        assertEquals(2, stats.unconfirmedCount());
        assertEquals(Set.of(9L, 10L), Set.copyOf(stats.unconfirmedUserIds()));
        assertEquals(1, stats.confirmationCounts().get("RECEIVED"));
        assertFalse(stats.expired());
    }

    @Test
    void administratorCanReadStatisticsForAnotherSender() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        broadcast.setSenderId(7L);
        when(broadcastMapper.selectById(21L)).thenReturn(broadcast);
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L, "admin"));
        when(receiverMapper.selectByBroadcastId(21L)).thenReturn(List.of());

        assertEquals(0, service.getStats(21L, 1L).targetCount());
    }

    @Test
    void administratorCancelsActiveBroadcastWithoutDeletingHistory() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L, "admin"));
        when(broadcastMapper.selectByIdForUpdate(21L)).thenReturn(broadcast);
        when(broadcastMapper.updateById(any(Broadcast.class))).thenReturn(1);

        Broadcast cancelled = service.cancel(21L, 1L);

        assertEquals("CANCELLED", cancelled.getStatus());
        assertNotNull(cancelled.getUpdateTime());
        verify(broadcastMapper).updateById(broadcast);
        verify(broadcastMapper, never()).deleteById(21L);
        verify(receiverMapper, never()).delete(any());
    }

    @Test
    void cancellingAnAlreadyCancelledBroadcastIsIdempotent() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        broadcast.setStatus("CANCELLED");
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L, "admin"));
        when(broadcastMapper.selectByIdForUpdate(21L)).thenReturn(broadcast);

        assertSame(broadcast, service.cancel(21L, 1L));

        verify(broadcastMapper, never()).updateById(any(Broadcast.class));
        verify(receiverMapper, never()).delete(any());
    }

    @Test
    void regularUserCannotCancelBroadcast() {
        when(userMapper.selectById(7L)).thenReturn(authorizedUser(7L, "alice"));

        assertThrows(AccessDeniedException.class, () -> service.cancel(21L, 7L));

        verify(broadcastMapper, never()).selectByIdForUpdate(21L);
    }

    @Test
    void cancelledBroadcastCannotBeConfirmed() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        broadcast.setStatus("CANCELLED");
        when(broadcastMapper.selectByIdForUpdate(21L)).thenReturn(broadcast);
        when(userMapper.selectById(8L)).thenReturn(activeUser(8L, "bob"));
        when(receiverMapper.selectReceiver(21L, 8L))
                .thenReturn(receiver(21L, 8L, "PENDING"));

        assertThrows(IllegalArgumentException.class,
                () -> service.confirm(21L, 8L, "web", confirmation("RECEIVED")));
    }

    private BroadcastCreateDTO validRequest(String scope) {
        BroadcastCreateDTO request = new BroadcastCreateDTO();
        request.setTitle("园区应急通知");
        request.setContent("请按预案执行并回报状态");
        request.setPriority("EMERGENCY");
        request.setScopeType(scope);
        request.setDeadlineAt(LocalDateTime.now().plusHours(1));
        return request;
    }

    private BroadcastConfirmDTO confirmation(String status) {
        BroadcastConfirmDTO request = new BroadcastConfirmDTO();
        request.setStatus(status);
        return request;
    }

    private Broadcast confirmableBroadcast(Long id, LocalDateTime deadline) {
        Broadcast broadcast = new Broadcast();
        broadcast.setId(id);
        broadcast.setSenderId(7L);
        broadcast.setConfirmationRequired(true);
        broadcast.setConfirmationOptions("[\"RECEIVED\",\"EXECUTED\",\"NEED_SUPPORT\"]");
        broadcast.setDeadlineAt(deadline);
        broadcast.setStatus("ACTIVE");
        return broadcast;
    }

    private User activeUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setStatus(1);
        user.setCanSendBroadcast(0);
        return user;
    }

    private User authorizedUser(Long id, String username) {
        User user = activeUser(id, username);
        user.setCanSendBroadcast(1);
        return user;
    }

    private BroadcastReceiver receiver(Long broadcastId, Long userId, String status) {
        BroadcastReceiver receiver = new BroadcastReceiver();
        receiver.setBroadcastId(broadcastId);
        receiver.setUserId(userId);
        receiver.setConfirmStatus(status);
        return receiver;
    }
}
