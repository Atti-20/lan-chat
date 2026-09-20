package com.lanchat.controller;

import com.lanchat.dto.TemporaryRoomCreateDTO;
import com.lanchat.dto.TemporaryRoomJoinDTO;
import com.lanchat.dto.TemporaryRoomVO;
import com.lanchat.security.LoginUser;
import com.lanchat.service.TemporaryRoomService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporaryRoomControllerTest {

    private TemporaryRoomService roomService;
    private TemporaryRoomController controller;

    @BeforeEach
    void setUp() {
        roomService = mock(TemporaryRoomService.class);
        controller = new TemporaryRoomController(roomService);
        LoginUser loginUser = new LoginUser(7L, "alice", "web", "access-token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAlwaysUsesAuthenticatedUser() {
        TemporaryRoomCreateDTO dto = new TemporaryRoomCreateDTO();
        TemporaryRoomVO room = new TemporaryRoomVO();
        room.setId(11L);
        when(roomService.createRoom(7L, dto)).thenReturn(room);

        var result = controller.createRoom(dto);

        assertEquals(200, result.getCode());
        assertEquals(11L, result.getData().getId());
        verify(roomService).createRoom(7L, dto);
    }

    @Test
    void joinAlwaysUsesAuthenticatedUser() {
        TemporaryRoomJoinDTO dto = new TemporaryRoomJoinDTO();
        dto.setRoomCode("ABCDEF123456");
        when(roomService.joinByCode(7L, "ABCDEF123456")).thenReturn(new TemporaryRoomVO());

        var result = controller.joinRoom(dto);

        assertEquals(200, result.getCode());
        verify(roomService).joinByCode(7L, "ABCDEF123456");
    }

    @Test
    void rejectsMissingJoinBodyBeforeServiceCall() {
        var result = controller.joinRoom(null);

        assertEquals(400, result.getCode());
        verify(roomService, never()).joinByCode(7L, null);
    }

    @Test
    void listDetailAndLeaveUseAuthenticatedUser() {
        when(roomService.getMyRooms(7L)).thenReturn(List.of());
        when(roomService.getRoom(11L, 7L)).thenReturn(new TemporaryRoomVO());

        assertEquals(200, controller.getMyRooms().getCode());
        assertEquals(200, controller.getRoom(11L).getCode());
        assertEquals(200, controller.leaveRoom(11L).getCode());

        verify(roomService).getMyRooms(7L);
        verify(roomService).getRoom(11L, 7L);
        verify(roomService).leaveRoom(11L, 7L);
    }
}
