package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.entity.ChatMessage;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.ChatMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin
public class ChatController {

    @Autowired
    private ChatMessageService chatMessageService;

    @GetMapping("/history/group")
    public Result<List<ChatMessage>> getGroupHistory(
            @RequestParam Long groupId,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(chatMessageService.getGroupHistory(groupId, limit));
    }

    @GetMapping("/history/private")
    public Result<List<ChatMessage>> getPrivateHistory(
            @RequestParam Long userId,
            @RequestParam Long targetId,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(chatMessageService.getPrivateHistory(userId, targetId, limit));
    }

    @PutMapping("/read")
    public Result<Void> markAsRead(@RequestParam Long fromUserId, @RequestParam Long toUserId) {
        chatMessageService.markAsRead(fromUserId, toUserId);
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
        chatMessageService.markAsBurned(messageId);
        return Result.success();
    }

    @GetMapping("/search")
    public Result<List<ChatMessage>> searchMessages(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "50") int limit) {
        Long userId = UserContextHolder.getCurrentUserId();
        return Result.success(chatMessageService.searchMessages(userId, keyword, limit));
    }
}
