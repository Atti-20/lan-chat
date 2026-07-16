package com.lanchat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.BroadcastConfirmDTO;
import com.lanchat.dto.BroadcastCreateDTO;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;
import com.lanchat.entity.ChatGroup;
import com.lanchat.entity.GroupMember;
import com.lanchat.entity.User;
import com.lanchat.mapper.BroadcastMapper;
import com.lanchat.mapper.BroadcastReceiverMapper;
import com.lanchat.mapper.GroupMemberMapper;
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
    private GroupMemberMapper groupMemberMapper;
    private GroupService groupService;
    private BroadcastServiceImpl service;

    @BeforeEach
    void setUp() {
        broadcastMapper = mock(BroadcastMapper.class);
        receiverMapper = mock(BroadcastReceiverMapper.class);
        userMapper = mock(UserMapper.class);
        groupMemberMapper = mock(GroupMemberMapper.class);
        groupService = mock(GroupService.class);
        service = new BroadcastServiceImpl(
                broadcastMapper,
                receiverMapper,
                userMapper,
                groupMemberMapper,
                groupService,
                new ObjectMapper()
        );
    }

    @Test
    void regularUserCannotBroadcastToEveryone() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "alice"));
        BroadcastCreateDTO request = validRequest("ALL");

        assertThrows(AccessDeniedException.class, () -> service.create(7L, request));

        verify(broadcastMapper, never()).insert(any(Broadcast.class));
    }

    @Test
    void explicitUserScopeAlsoRequiresNodeAdministrator() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "alice"));
        BroadcastCreateDTO request = validRequest("USERS");
        request.setReceiverIds(List.of(8L));

        assertThrows(AccessDeniedException.class, () -> service.create(7L, request));

        verify(userMapper, never()).selectBatchIds(any());
        verify(broadcastMapper, never()).insert(any(Broadcast.class));
    }

    @Test
    void nodeAdministratorCanBroadcastToAllActiveUsers() {
        User admin = activeUser(1L, "admin");
        User bob = activeUser(8L, "bob");
        User disabled = activeUser(9L, "disabled");
        disabled.setStatus(0);
        when(userMapper.selectById(1L)).thenReturn(admin);
        when(userMapper.selectList(any())).thenReturn(List.of(admin, bob, disabled));
        when(broadcastMapper.insert(any(Broadcast.class))).thenAnswer(invocation -> {
            Broadcast value = invocation.getArgument(0);
            value.setId(44L);
            return 1;
        });
        when(receiverMapper.insert(any(BroadcastReceiver.class))).thenReturn(1);

        Broadcast created = service.create(1L, validRequest("ALL"));

        assertEquals(44L, created.getId());
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(receiverMapper, times(2)).insert(receiverCaptor.capture());
        assertEquals(Set.of(1L, 8L), receiverCaptor.getAllValues().stream()
                .map(BroadcastReceiver::getUserId)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(receiverCaptor.getAllValues().stream()
                .allMatch(item -> "NOT_REQUIRED".equals(item.getConfirmStatus())));
    }

    @Test
    void groupAdministratorCreatesRecipientSnapshot() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "alice"));
        ChatGroup group = new ChatGroup();
        group.setId(12L);
        when(groupService.getGroupInfo(12L)).thenReturn(group);
        when(groupService.getMemberRole(12L, 7L)).thenReturn(1);
        when(groupMemberMapper.selectList(any())).thenReturn(List.of(
                groupMember(12L, 7L),
                groupMember(12L, 8L),
                groupMember(12L, 8L)
        ));
        when(broadcastMapper.insert(any(Broadcast.class))).thenAnswer(invocation -> {
            Broadcast value = invocation.getArgument(0);
            value.setId(99L);
            return 1;
        });
        when(receiverMapper.insert(any(BroadcastReceiver.class))).thenReturn(1);

        BroadcastCreateDTO request = validRequest("GROUP");
        request.setGroupId(12L);
        request.setConfirmationRequired(true);
        Broadcast created = service.create(7L, request);

        assertEquals(99L, created.getId());
        assertEquals("GROUP", created.getScopeType());
        assertEquals(12L, created.getScopeGroupId());
        assertTrue(created.getConfirmationOptions().contains("NEED_SUPPORT"));
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(receiverMapper, times(2)).insert(receiverCaptor.capture());
        assertEquals(Set.of(7L, 8L), receiverCaptor.getAllValues().stream()
                .map(BroadcastReceiver::getUserId)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(receiverCaptor.getAllValues().stream()
                .allMatch(item -> "PENDING".equals(item.getConfirmStatus())));
    }

    @Test
    void groupMemberWithoutAdministrativeRoleCannotCreateBroadcast() {
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "alice"));
        ChatGroup group = new ChatGroup();
        group.setId(12L);
        when(groupService.getGroupInfo(12L)).thenReturn(group);
        when(groupService.getMemberRole(12L, 7L)).thenReturn(0);
        BroadcastCreateDTO request = validRequest("GROUP");
        request.setGroupId(12L);

        assertThrows(AccessDeniedException.class, () -> service.create(7L, request));

        verify(groupMemberMapper, never()).selectList(any());
    }

    @Test
    void repeatingSameConfirmationDoesNotWriteAgain() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        BroadcastReceiver confirmed = receiver(21L, 8L, "EXECUTED");
        confirmed.setConfirmedAt(LocalDateTime.now());
        when(broadcastMapper.selectById(21L)).thenReturn(broadcast);
        when(receiverMapper.selectReceiver(21L, 8L)).thenReturn(confirmed);
        BroadcastConfirmDTO request = new BroadcastConfirmDTO();
        request.setStatus("executed");

        BroadcastReceiver result = service.confirm(21L, 8L, "web", request);

        assertEquals(confirmed, result);
        verify(receiverMapper, never()).confirmIfPending(any(), any(), any(), any());
    }

    @Test
    void pendingAndNotRequiredAreReservedConfirmationValues() {
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L, "admin"));
        when(userMapper.selectList(any())).thenReturn(List.of(activeUser(1L, "admin")));
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
        when(broadcastMapper.selectById(21L)).thenReturn(broadcast);
        when(receiverMapper.selectReceiver(21L, 8L)).thenReturn(pending, confirmed);
        when(receiverMapper.confirmIfPending(21L, 8L, "NEED_SUPPORT", "desktop")).thenReturn(1);
        BroadcastConfirmDTO request = new BroadcastConfirmDTO();
        request.setStatus("need_support");

        BroadcastReceiver result = service.confirm(21L, 8L, "desktop", request);

        assertEquals("NEED_SUPPORT", result.getConfirmStatus());
        verify(receiverMapper).confirmIfPending(21L, 8L, "NEED_SUPPORT", "desktop");
    }

    @Test
    void concurrentDifferentConfirmationIsRejected() {
        Broadcast broadcast = confirmableBroadcast(21L, LocalDateTime.now().plusMinutes(10));
        BroadcastReceiver pending = receiver(21L, 8L, "PENDING");
        BroadcastReceiver otherWinner = receiver(21L, 8L, "RECEIVED");
        otherWinner.setConfirmedAt(LocalDateTime.now());
        when(broadcastMapper.selectById(21L)).thenReturn(broadcast);
        when(receiverMapper.selectReceiver(21L, 8L)).thenReturn(pending, otherWinner);
        when(receiverMapper.confirmIfPending(21L, 8L, "EXECUTED", "web")).thenReturn(0);
        BroadcastConfirmDTO request = new BroadcastConfirmDTO();
        request.setStatus("EXECUTED");

        assertThrows(IllegalArgumentException.class,
                () -> service.confirm(21L, 8L, "web", request));
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
        when(receiverMapper.selectByBroadcastId(21L)).thenReturn(List.of(received, viewed, untouched));

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

    private BroadcastCreateDTO validRequest(String scope) {
        BroadcastCreateDTO request = new BroadcastCreateDTO();
        request.setTitle("园区应急通知");
        request.setContent("请按预案执行并回报状态");
        request.setPriority("EMERGENCY");
        request.setScopeType(scope);
        request.setDeadlineAt(LocalDateTime.now().plusHours(1));
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
        return user;
    }

    private GroupMember groupMember(Long groupId, Long userId) {
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        return member;
    }

    private BroadcastReceiver receiver(Long broadcastId, Long userId, String status) {
        BroadcastReceiver receiver = new BroadcastReceiver();
        receiver.setBroadcastId(broadcastId);
        receiver.setUserId(userId);
        receiver.setConfirmStatus(status);
        return receiver;
    }
}
