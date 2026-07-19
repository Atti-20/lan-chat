package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.GroupCreateDTO;
import com.lanchat.dto.GroupMemberMuteDTO;
import com.lanchat.dto.GroupUpdateDTO;
import com.lanchat.entity.ChatGroup;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/group")
public class GroupController {

    @Autowired
    private GroupService groupService;

    @PostMapping
    public Result<ChatGroup> createGroup(@RequestBody GroupCreateDTO dto) {
        Long userId = UserContextHolder.getCurrentUserId();
        return Result.success(groupService.createGroup(userId, dto));
    }

    @GetMapping("/{groupId}")
    public Result<ChatGroup> getGroupInfo(@PathVariable Long groupId) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (!groupService.isMember(groupId, userId)) {
            return Result.forbidden("你不是该群成员");
        }
        ChatGroup group = groupService.getGroupInfo(groupId);
        if (group == null) {
            return Result.error("群组不存在");
        }
        return Result.success(group);
    }

    @PutMapping("/{groupId}")
    public Result<Void> updateGroup(@PathVariable Long groupId, @RequestBody GroupUpdateDTO dto) {
        Long userId = UserContextHolder.getCurrentUserId();
        groupService.updateGroup(groupId, userId, dto);
        return Result.success();
    }

    @GetMapping("/my")
    public Result<List<Map<String, Object>>> getMyGroups() {
        Long userId = UserContextHolder.getCurrentUserId();
        return Result.success(groupService.getUserGroupsWithLastMessage(userId));
    }

    @GetMapping("/{groupId}/members")
    public Result<List<Map<String, Object>>> getGroupMembers(@PathVariable Long groupId) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (!groupService.isMember(groupId, userId)) {
            return Result.forbidden("你不是该群成员");
        }
        return Result.success(groupService.getGroupMembers(groupId));
    }

    @PostMapping("/{groupId}/members")
    public Result<Void> addMembers(@PathVariable Long groupId, @RequestBody List<Long> userIds) {
        Long userId = UserContextHolder.getCurrentUserId();
        groupService.addMembers(groupId, userId, userIds);
        return Result.success();
    }

    @DeleteMapping("/{groupId}/members/{memberId}")
    public Result<Void> removeMember(@PathVariable Long groupId, @PathVariable Long memberId) {
        Long userId = UserContextHolder.getCurrentUserId();
        groupService.removeMember(groupId, userId, memberId);
        return Result.success();
    }

    @PostMapping("/{groupId}/leave")
    public Result<Void> leaveGroup(@PathVariable Long groupId) {
        Long userId = UserContextHolder.getCurrentUserId();
        groupService.leaveGroup(groupId, userId);
        return Result.success();
    }

    @PutMapping("/{groupId}/transfer")
    public Result<Void> transferOwner(@PathVariable Long groupId, @RequestParam Long newOwnerId) {
        Long userId = UserContextHolder.getCurrentUserId();
        groupService.transferOwner(groupId, userId, newOwnerId);
        return Result.success();
    }

    @PutMapping("/{groupId}/admin")
    public Result<Void> setAdmin(@PathVariable Long groupId, @RequestParam Long userId, @RequestParam Boolean isAdmin) {
        Long operatorId = UserContextHolder.getCurrentUserId();
        if (isAdmin == null) return Result.error(400, "管理员参数不完整");
        groupService.setAdmin(groupId, operatorId, userId, isAdmin);
        return Result.success();
    }

    @PutMapping("/{groupId}/mute")
    public Result<Void> muteMember(@PathVariable Long groupId, @RequestBody GroupMemberMuteDTO dto) {
        Long operatorId = UserContextHolder.getCurrentUserId();
        if (dto == null || dto.getUserId() == null || dto.getMuteMinutes() == null) {
            return Result.error(400, "禁言参数不完整");
        }
        groupService.muteMember(groupId, operatorId, dto.getUserId(), dto.getMuteMinutes());
        return Result.success();
    }

    @DeleteMapping("/{groupId}/dissolve")
    public Result<Void> dissolveGroup(@PathVariable Long groupId) {
        Long userId = UserContextHolder.getCurrentUserId();
        groupService.dissolveGroup(groupId, userId);
        return Result.success();
    }
}
