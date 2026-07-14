package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.entity.User;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    @Autowired
    private UserService userService;

    // 检查当前操作者是否 admin
    private void checkAdminPermission() {
        if (UserContextHolder.getCurrentUser() == null) {
            throw new AccessDeniedException("请先登录");
        }
        String currentUsername = UserContextHolder.getCurrentUser().getUsername();
        if(!"admin".equals(currentUsername)) {
            throw new AccessDeniedException("警告：越权操作！");
        }
    }

    // 1、获取用户列表
    @GetMapping("/users")
    public Result listAllUsers() {
        checkAdminPermission();
        List<User> userList = userService.list();
        // password字段置空
        userList.forEach(user -> user.setPassword("******"));
        return Result.success(userList);
    }

    /**
     * 2、封禁/解禁用户
     * @param userId
     * @param status（1:正常，0:封禁）
     */
    @PostMapping("/user/status")
    public Result changUserStatus(@RequestParam Long userId, @RequestParam Integer status) {
        checkAdminPermission();
        if (status == null || (status != 0 && status != 1)) {
            return Result.error(400, "用户状态只能为0或1");
        }
        // 保护机制：管理员不能封禁自己
        User targetUser = userService.getById(userId);
        if (targetUser == null) return Result.error(404, "用户不存在");
        if("admin".equals(targetUser.getUsername())) {
            return Result.error("操作失败：不能封禁管理员账号！");
        }

        User userUpdate = new User();
        userUpdate.setId(userId);
        userUpdate.setStatus(status);
        boolean success = userService.updateById(userUpdate);

        return success ? Result.success(status == 0 ? "用户已被封禁！" : "用户已解禁！") : Result.error("操作失败");
    }

    /**
     * 3、设置全局禁言
     */
    @PostMapping("/user/mute")
    public Result muteUserGlobally(@RequestParam Long userId,
                                   @RequestParam String muteStart,
                                   @RequestParam String muteEnd) {
        checkAdminPermission();
        boolean success = userService.setMutePeriod(userId, muteStart, muteEnd);
        return success ? Result.success("禁言时段设置成功") : Result.error("操作失败");
    }

    /**
     * 4、删除用户
     */
    @DeleteMapping("/user/{userId}")
    public Result deleteUser(@PathVariable Long userId) {
        checkAdminPermission();
        try {
            userService.deleteUserByAdmin(userId);
            return Result.success("用户已删除");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
