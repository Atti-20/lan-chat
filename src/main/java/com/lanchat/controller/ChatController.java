package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.entity.ChatMessage;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.ChatMessageService;
import com.lanchat.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin
public class ChatController {

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private GroupService groupService;

    @GetMapping("/history/group")
    public Result<List<ChatMessage>> getGroupHistory(
            @RequestParam Long groupId,
            @RequestParam(defaultValue = "50") int limit) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (!groupService.isMember(groupId, userId)) {
            return Result.forbidden("你不是该群成员");
        }
        return Result.success(chatMessageService.getGroupHistory(groupId, limit));
    }

    @GetMapping("/history/private")
    public Result<List<ChatMessage>> getPrivateHistory(
            @RequestParam(required = false) Long userId,
            @RequestParam Long targetId,
            @RequestParam(defaultValue = "50") int limit) {
        Long currentUserId = UserContextHolder.getCurrentUserId();
        return Result.success(chatMessageService.getPrivateHistory(currentUserId, targetId, limit));
    }

    @PutMapping("/read")
    public Result<Void> markAsRead(@RequestParam Long fromUserId,
                                   @RequestParam(required = false) Long toUserId) {
        Long currentUserId = UserContextHolder.getCurrentUserId();
        chatMessageService.markAsRead(fromUserId, currentUserId);
        return Result.success();
    }

    @PostMapping("/recall")
    public Result<Void> recallMessage(@RequestParam String messageId) {
        Long userId = UserContextHolder.getCurrentUserId();
        chatMessageService.recallMessage(messageId, userId);
        return Result.success();
    }

    @PostMapping("/burn")
    public Result<Void> burnMessage(@RequestParam String messageId) {
        Long userId = UserContextHolder.getCurrentUserId();
        chatMessageService.markAsBurned(messageId, userId);
        return Result.success();
    }

    @GetMapping("/search")
    public Result<List<ChatMessage>> searchMessages(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "50") int limit) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (keyword == null || keyword.trim().length() < 2) {
            return Result.error(400, "搜索关键词至少2个字符");
        }
        return Result.success(chatMessageService.searchMessages(userId, keyword, limit));
    }
}
