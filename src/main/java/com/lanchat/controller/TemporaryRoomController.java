package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.TemporaryRoomCreateDTO;
import com.lanchat.dto.TemporaryRoomJoinDTO;
import com.lanchat.dto.TemporaryRoomVO;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.TemporaryRoomService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rooms")
public class TemporaryRoomController {

    private final TemporaryRoomService roomService;

    public TemporaryRoomController(TemporaryRoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    public Result<TemporaryRoomVO> createRoom(@RequestBody TemporaryRoomCreateDTO dto) {
        return Result.success(roomService.createRoom(UserContextHolder.getCurrentUserId(), dto));
    }

    @PostMapping("/join")
    public Result<TemporaryRoomVO> joinRoom(@RequestBody TemporaryRoomJoinDTO dto) {
        if (dto == null) return Result.error(400, "房间码不能为空");
        return Result.success(roomService.joinByCode(
                UserContextHolder.getCurrentUserId(), dto.getRoomCode()));
    }

    @GetMapping
    public Result<List<TemporaryRoomVO>> getMyRooms() {
        return Result.success(roomService.getMyRooms(UserContextHolder.getCurrentUserId()));
    }

    @GetMapping("/{roomId}")
    public Result<TemporaryRoomVO> getRoom(@PathVariable Long roomId) {
        return Result.success(roomService.getRoom(roomId, UserContextHolder.getCurrentUserId()));
    }

    @PostMapping("/{roomId}/leave")
    public Result<Void> leaveRoom(@PathVariable Long roomId) {
        roomService.leaveRoom(roomId, UserContextHolder.getCurrentUserId());
        return Result.success();
    }
}
