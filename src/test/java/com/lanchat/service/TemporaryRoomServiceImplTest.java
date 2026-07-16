package com.lanchat.service;

import com.lanchat.dto.TemporaryRoomCreateDTO;
import com.lanchat.entity.TemporaryRoom;
import com.lanchat.mapper.TemporaryRoomMapper;
import com.lanchat.service.impl.TemporaryRoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporaryRoomServiceImplTest {

    private TemporaryRoomMapper roomMapper;
    private ConversationService conversationService;
    private TemporaryRoomServiceImpl service;

    @BeforeEach
    void setUp() {
        roomMapper = mock(TemporaryRoomMapper.class);
        conversationService = mock(ConversationService.class);
        service = new TemporaryRoomServiceImpl(
                roomMapper, conversationService, mock(ApplicationEventPublisher.class));
    }

    @Test
    void createsTemporaryConversationAndOwnerMembership() {
        when(roomMapper.countByRoomCode(any())).thenReturn(0L);
        when(roomMapper.insert(any(TemporaryRoom.class))).thenAnswer(invocation -> {
            TemporaryRoom room = invocation.getArgument(0);
            room.setId(31L);
            return 1;
        });
        when(conversationService.ensureTemporaryConversation(31L)).thenReturn("temporary:31");
        when(roomMapper.selectMemberRole(31L, 7L)).thenReturn("OWNER");
        when(roomMapper.countActiveMembers(31L)).thenReturn(1L);

        var result = service.createRoom(7L, validCreateDto("archive"));

        assertEquals(31L, result.getId());
        assertEquals("temporary:31", result.getConversationId());
        assertEquals("ARCHIVE", result.getExpireAction());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals("OWNER", result.getCurrentUserRole());
        assertTrue(result.getRoomCode().matches("^[A-F0-9]{12}$"));
        verify(conversationService).ensureTemporaryConversation(31L);
        verify(conversationService).addConversationMember("temporary:31", 7L, "OWNER");
    }

    @Test
    void joinsActiveRoomByNormalizedCode() {
        TemporaryRoom room = room(9L, 5L, "ACTIVE", "FREEZE");
        room.setRoomCode("ABCDEF123456");
        room.setExpiresAt(LocalDateTime.now().plusHours(2));
        room.setMaxMembers(5);
        when(roomMapper.selectByRoomCodeForUpdate("ABCDEF123456")).thenReturn(room);
        when(roomMapper.selectMemberRole(9L, 8L)).thenReturn(null, "MEMBER");
        when(roomMapper.countActiveMembers(9L)).thenReturn(2L, 3L);
        when(conversationService.ensureTemporaryConversation(9L)).thenReturn("temporary:9");

        var result = service.joinByCode(8L, "  abcdef123456 ");

        assertEquals("MEMBER", result.getCurrentUserRole());
        assertEquals(3L, result.getMemberCount());
        verify(conversationService).addConversationMember("temporary:9", 8L, "MEMBER");
        verify(roomMapper).touch(eq(9L), any(LocalDateTime.class));
    }

    @Test
    void rejectsJoinWhenRoomIsFull() {
        TemporaryRoom room = room(9L, 5L, "ACTIVE", "FREEZE");
        room.setRoomCode("ABCDEF123456");
        room.setExpiresAt(LocalDateTime.now().plusHours(2));
        room.setMaxMembers(2);
        when(roomMapper.selectByRoomCodeForUpdate("ABCDEF123456")).thenReturn(room);
        when(roomMapper.countActiveMembers(9L)).thenReturn(2L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.joinByCode(8L, "ABCDEF123456"));

        assertEquals("临时房间成员数量已达上限", exception.getMessage());
        verify(conversationService, never()).addConversationMember(any(), any(), any());
    }

    @Test
    void ownerCannotLeaveRoom() {
        TemporaryRoom room = room(9L, 7L, "ACTIVE", "FREEZE");
        when(roomMapper.selectByIdForUpdate(9L)).thenReturn(room);
        when(roomMapper.selectMemberRole(9L, 7L)).thenReturn("OWNER");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.leaveRoom(9L, 7L));

        assertEquals("房间所有者不能直接离开临时房间", exception.getMessage());
        verify(conversationService, never()).removeConversationMember(any(), any());
    }

    @Test
    void appliesFreezeArchiveAndDestroyPoliciesIdempotently() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 9, 30);
        TemporaryRoom freeze = room(1L, 7L, "ACTIVE", "FREEZE");
        TemporaryRoom archive = room(2L, 7L, "ACTIVE", "ARCHIVE");
        TemporaryRoom destroy = room(3L, 7L, "ACTIVE", "DESTROY");
        when(roomMapper.selectExpiredActiveRooms(now)).thenReturn(List.of(freeze, archive, destroy));
        when(roomMapper.transitionExpiredRoom(eq(1L), eq("FROZEN"), eq(now))).thenReturn(1);
        when(roomMapper.transitionExpiredRoom(eq(2L), eq("ARCHIVED"), eq(now))).thenReturn(1);
        when(roomMapper.transitionExpiredRoom(eq(3L), eq("DESTROYED"), eq(now))).thenReturn(1);

        int processed = service.processExpiredRooms(now);

        assertEquals(3, processed);
        verify(conversationService).updateStatus("temporary:1", "READ_ONLY");
        verify(conversationService).updateStatus("temporary:2", "ARCHIVED");
        verify(conversationService).removeAllConversationMembers("temporary:3");
        verify(conversationService).updateStatus("temporary:3", "DESTROYED");
    }

    @Test
    void skipsLifecycleWorkWhenAnotherWorkerWonTransition() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 9, 30);
        TemporaryRoom room = room(1L, 7L, "ACTIVE", "DESTROY");
        when(roomMapper.selectExpiredActiveRooms(now)).thenReturn(List.of(room));
        when(roomMapper.transitionExpiredRoom(1L, "DESTROYED", now)).thenReturn(0);

        assertEquals(0, service.processExpiredRooms(now));
        verify(conversationService, never()).removeAllConversationMembers(any());
        verify(conversationService, never()).updateStatus(any(), any());
    }

    private TemporaryRoomCreateDTO validCreateDto(String action) {
        TemporaryRoomCreateDTO dto = new TemporaryRoomCreateDTO();
        dto.setRoomName("应急协作室");
        dto.setPurpose("现场联络");
        dto.setExpiresAt(LocalDateTime.now().plusDays(1));
        dto.setMaxMembers(20);
        dto.setExpireAction(action);
        return dto;
    }

    private TemporaryRoom room(Long id, Long ownerId, String status, String action) {
        TemporaryRoom room = new TemporaryRoom();
        room.setId(id);
        room.setRoomName("临时房间" + id);
        room.setPurpose("");
        room.setOwnerId(ownerId);
        room.setRoomCode("ABCDEF123456");
        room.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        room.setMaxMembers(20);
        room.setAllowGuests(0);
        room.setAllowMemberInvite(1);
        room.setAllowFileUpload(1);
        room.setAllowFileDownload(1);
        room.setAllowForward(0);
        room.setMessageRetentionDays(7);
        room.setAllowExternalSync(0);
        room.setExpireAction(action);
        room.setStatus(status);
        room.setCreateTime(LocalDateTime.now().minusDays(1));
        room.setUpdateTime(LocalDateTime.now().minusDays(1));
        return room;
    }
}
