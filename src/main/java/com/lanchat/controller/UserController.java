package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.ChangePasswordDTO;
import com.lanchat.dto.DeviceLoginVO;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.security.LoginUser;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.UserService;
import com.lanchat.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private ChatWebSocketHandler webSocketHandler;

    @GetMapping("/info")
    public Result<User> getCurrentUserInfo() {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("请先登录");
        }
        return Result.success(userService.getUserInfo(userId));
    }

    @GetMapping("/{id}")
    public Result<User> getUserInfo(@PathVariable Long id) {
        User user = userService.getUserInfo(id);
        if (user == null) {
            return Result.error("用户不存在");
        }
        return Result.success(user);
    }

    @GetMapping("/online")
    public Result<List<User>> getOnlineUsers() {
        return Result.success(userService.getOnlineUsers());
    }

    @GetMapping("/search")
    public Result<List<User>> searchUsers(@RequestParam String keyword) {
        return Result.success(userService.searchUsers(keyword));
    }

    @GetMapping("/devices")
    public Result<List<DeviceLoginVO>> getDevices() {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("请先登录");
        }
        LoginUser loginUser = UserContextHolder.getCurrentUser();
        DeviceLogin currentDevice = loginUser == null ? null : userService.getActiveDevice(
                loginUser.getToken(), userId, loginUser.getDeviceType());
        Long currentDeviceId = currentDevice == null ? null : currentDevice.getId();
        List<DeviceLoginVO> devices = userService.getDevices(userId).stream()
                .map(device -> DeviceLoginVO.from(device,
                        currentDeviceId != null && currentDeviceId.equals(device.getId())))
                .toList();
        return Result.success(devices);
    }

    @DeleteMapping("/devices/{deviceId}")
    public Result<Void> logoutDevice(@PathVariable Long deviceId) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("请先登录");
        }
        userService.logoutDevice(userId, deviceId);
        return Result.success();
    }

    /**
     * 设置全局免打扰时段
     * PRD: 全局免打扰时段设置（如22:00-8:00），期间不弹推送，仅留存未读计数
     */
    @PutMapping("/mute-period")
    public Result<Void> setMutePeriod(
            @RequestParam(required = false) String muteStart,
            @RequestParam(required = false) String muteEnd) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("请先登录");
        }
        userService.setMutePeriod(userId, muteStart, muteEnd);
        return Result.success();
    }

    /**
     * 查询当前是否处于免打扰时段
     */
    @GetMapping("/mute-status")
    public Result<Boolean> getMuteStatus() {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("请先登录");
        }
        return Result.success(userService.isInMutePeriod(userId));
    }

    /**
     * 修改个人资料（昵称、头像）
     */
    @PutMapping("/profile")
    public Result<User> updateProfile(@RequestBody java.util.Map<String, String> body) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("请先登录");
        }
        if (body == null) return Result.error(400, "个人资料参数不能为空");
        try {
            String nickname = body.get("nickname");
            String avatar = body.get("avatar");
            userService.updateProfile(userId, nickname, avatar);
            User updateUser = userService.getUserInfo(userId);
            // 通知其他在线客户端刷新头像和昵称
            webSocketHandler.sendProfileUpdated(updateUser);
            // 返回更新后的用户信息
            return Result.success(updateUser);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 修改密码
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordDTO dto) {
        try {
            userService.changePassword(dto);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
