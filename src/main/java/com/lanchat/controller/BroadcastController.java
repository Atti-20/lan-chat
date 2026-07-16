package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.BroadcastConfirmDTO;
import com.lanchat.dto.BroadcastCreateDTO;
import com.lanchat.dto.BroadcastDetailDTO;
import com.lanchat.dto.BroadcastStatsDTO;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;
import com.lanchat.security.LoginUser;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.BroadcastService;
import com.lanchat.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/broadcast")
@CrossOrigin
public class BroadcastController {

    private final BroadcastService broadcastService;

    @Autowired(required = false)
    private ChatWebSocketHandler webSocketHandler;

    public BroadcastController(BroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    @PostMapping
    public Result<Broadcast> create(@RequestBody BroadcastCreateDTO request) {
        LoginUser user = currentUser();
        Broadcast created = broadcastService.create(user.getUserId(), request);
        if (webSocketHandler != null) webSocketHandler.publishBroadcast(created.getId());
        return Result.success(created);
    }

    /** Broadcasts created by or addressed to the current user; administrators see all. */
    @GetMapping
    public Result<List<Broadcast>> list() {
        LoginUser user = currentUser();
        return Result.success(broadcastService.listVisible(user.getUserId()));
    }

    /** Active offline work that has not been viewed or still needs confirmation. */
    @GetMapping("/pending")
    public Result<List<Broadcast>> pending() {
        LoginUser user = currentUser();
        return Result.success(broadcastService.listPending(user.getUserId()));
    }

    @GetMapping("/{broadcastId}")
    public Result<BroadcastDetailDTO> detail(@PathVariable Long broadcastId) {
        LoginUser user = currentUser();
        return Result.success(broadcastService.getDetail(broadcastId, user.getUserId()));
    }

    @PostMapping("/{broadcastId}/view")
    public Result<BroadcastReceiver> view(@PathVariable Long broadcastId) {
        LoginUser user = currentUser();
        BroadcastReceiver viewed = broadcastService.markViewed(broadcastId, user.getUserId());
        if (webSocketHandler != null) {
            webSocketHandler.notifyBroadcastUpdated(broadcastId, user.getUserId());
        }
        return Result.success(viewed);
    }

    @PostMapping("/{broadcastId}/confirm")
    public Result<BroadcastReceiver> confirm(@PathVariable Long broadcastId,
                                             @RequestBody BroadcastConfirmDTO request) {
        LoginUser user = currentUser();
        BroadcastReceiver confirmed = broadcastService.confirm(
                broadcastId,
                user.getUserId(),
                user.getDeviceType(),
                request
        );
        if (webSocketHandler != null) {
            webSocketHandler.notifyBroadcastUpdated(broadcastId, user.getUserId());
        }
        return Result.success(confirmed);
    }

    @GetMapping("/{broadcastId}/stats")
    public Result<BroadcastStatsDTO> stats(@PathVariable Long broadcastId) {
        LoginUser user = currentUser();
        return Result.success(broadcastService.getStats(broadcastId, user.getUserId()));
    }

    private LoginUser currentUser() {
        LoginUser user = UserContextHolder.getCurrentUser();
        if (user == null) throw new AccessDeniedException("请先登录");
        return user;
    }
}
