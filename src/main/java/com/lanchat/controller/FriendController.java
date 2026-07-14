package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.FriendHandleDTO;
import com.lanchat.dto.FriendRequestDTO;
import com.lanchat.entity.FriendRequest;
import com.lanchat.entity.Friendship;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.FriendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/friend")
@CrossOrigin
public class FriendController {

    @Autowired
    private FriendService friendService;

    @PostMapping("/request")
    public Result<Void> sendFriendRequest(@RequestBody FriendRequestDTO dto) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (dto == null || dto.getToUserId() == null) {
            return Result.error(400, "好友申请参数不完整");
        }
        friendService.sendFriendRequest(userId, dto.getToUserId(), dto.getMessage());
        return Result.success();
    }

    @GetMapping("/requests")
    public Result<List<FriendRequest>> getFriendRequests() {
        Long userId = UserContextHolder.getCurrentUserId();
        return Result.success(friendService.getFriendRequests(userId));
    }

    @PostMapping("/handle")
    public Result<Void> handleFriendRequest(@RequestBody FriendHandleDTO dto) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (dto == null || dto.getRequestId() == null || dto.getAccept() == null) {
            return Result.error(400, "好友申请处理参数不完整");
        }
        friendService.handleFriendRequest(dto.getRequestId(), userId, dto.getAccept());
        return Result.success();
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> getFriendList() {
        Long userId = UserContextHolder.getCurrentUserId();
        return Result.success(friendService.getFriendListWithInfo(userId));
    }

    @DeleteMapping("/{friendId}")
    public Result<Void> deleteFriend(@PathVariable Long friendId) {
        Long userId = UserContextHolder.getCurrentUserId();
        friendService.deleteFriend(userId, friendId);
        return Result.success();
    }

    @PutMapping("/{friendId}/block")
    public Result<Void> toggleBlock(@PathVariable Long friendId) {
        Long userId = UserContextHolder.getCurrentUserId();
        friendService.toggleBlock(userId, friendId);
        return Result.success();
    }

    @PutMapping("/{friendId}/remark")
    public Result<Void> setRemark(@PathVariable Long friendId, @RequestParam String remark) {
        Long userId = UserContextHolder.getCurrentUserId();
        friendService.setRemark(userId, friendId, remark);
        return Result.success();
    }

    @PutMapping("/{friendId}/group")
    public Result<Void> setGroup(@PathVariable Long friendId, @RequestParam String groupName) {
        Long userId = UserContextHolder.getCurrentUserId();
        friendService.setGroup(userId, friendId, groupName);
        return Result.success();
    }

    @PutMapping("/{friendId}/pin")
    public Result<Void> togglePin(@PathVariable Long friendId) {
        Long userId = UserContextHolder.getCurrentUserId();
        friendService.togglePin(userId, friendId);
        return Result.success();
    }

    @PutMapping("/{friendId}/mute")
    public Result<Void> toggleMute(@PathVariable Long friendId) {
        Long userId = UserContextHolder.getCurrentUserId();
        friendService.toggleMute(userId, friendId);
        return Result.success();
    }
}
