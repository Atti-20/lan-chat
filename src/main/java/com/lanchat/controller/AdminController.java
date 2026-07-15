package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.AdminDiagnostics;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.dto.RuntimeLogSnapshot;
import com.lanchat.entity.User;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.NodeDiagnosticsService;
import com.lanchat.service.RuntimeLogService;
import com.lanchat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired
    private UserService userService;

    @Autowired
    private NodeDiagnosticsService nodeDiagnosticsService;

    @Autowired
    private RuntimeLogService runtimeLogService;

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

    /** Private deployments create regular accounts through the protected admin console. */
    @PostMapping("/users")
    public Result<Void> createUser(@RequestBody RegisterDTO dto) {
        checkAdminPermission();
        boolean success = userService.register(dto);
        if (!success) return Result.error(409, "用户名已存在");
        log.info("Administrator {} created account {}",
                UserContextHolder.getCurrentUser().getUsername(), dto.getUsername().trim());
        return Result.success();
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

    /** Detailed dependency, storage, JVM and WebSocket diagnostics. */
    @GetMapping("/diagnostics")
    public Result<AdminDiagnostics> diagnostics() {
        checkAdminPermission();
        return Result.success(nodeDiagnosticsService.adminDiagnostics());
    }

    /** Bounded and parsed view of the active process log. */
    @GetMapping("/logs")
    public Result<RuntimeLogSnapshot> runtimeLogs(
            @RequestParam(defaultValue = "300") int limit,
            @RequestParam(defaultValue = "ALL") String level,
            @RequestParam(defaultValue = "") String keyword) {
        checkAdminPermission();
        return Result.success(runtimeLogService.read(limit, level, keyword));
    }

    /** Streams the complete active log file without accepting a client-controlled path. */
    @GetMapping("/logs/export")
    public ResponseEntity<Resource> exportRuntimeLog() {
        checkAdminPermission();
        var exported = runtimeLogService.openExport();
        if (exported.isEmpty()) return ResponseEntity.notFound().build();

        RuntimeLogService.LogExport value = exported.get();
        log.info("Administrator {} exported the active runtime log",
                UserContextHolder.getCurrentUser().getUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(value.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(value.resource());
    }
}
