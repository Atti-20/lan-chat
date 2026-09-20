package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.control.rbac.PermissionCode;
import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.admin.ControlAdminOperationService;
import com.lanchat.dto.AdminDiagnostics;
import com.lanchat.dto.AdminPhysicalErasureDTO;
import com.lanchat.dto.AdminResetPasswordDTO;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.dto.RuntimeLogSnapshot;
import com.lanchat.entity.User;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.NodeDiagnosticsService;
import com.lanchat.service.RuntimeLogService;
import com.lanchat.service.UserService;
import com.lanchat.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private ControlAuditService controlAuditService;

    @Autowired
    private ControlAdminOperationService adminOperationService;

    @Autowired(required = false)
    private ChatWebSocketHandler webSocketHandler;

    private void requirePermission(PermissionCode permissionCode) {
        authorizationService.requireCurrentUserPermission(permissionCode);
    }

    // 1、获取用户列表
    @GetMapping("/users")
    public Result listAllUsers() {
        requirePermission(PermissionCode.USER_READ);
        List<User> userList = userService.list();
        // password字段置空
        userList.forEach(user -> user.setPassword("******"));
        return Result.success(userList);
    }

    /** Private deployments create regular accounts through the protected admin console. */
    @PostMapping("/users")
    public Result<Void> createUser(@RequestBody RegisterDTO dto) {
        requirePermission(PermissionCode.USER_CREATE);
        boolean success = adminOperationService.createUser(dto);
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
        requirePermission(PermissionCode.USER_DISABLE);
        if (status == null || (status != 0 && status != 1)) {
            return Result.error(400, "用户状态只能为0或1");
        }
        User targetUser = userService.getById(userId);
        if (targetUser == null) return Result.error(404, "用户不存在");

        boolean success = adminOperationService.setStatus(userId, status);

        return success ? Result.success(status == 0 ? "用户已被封禁！" : "用户已解禁！") : Result.error("操作失败");
    }

    /**
     * 3、设置全局禁言
     */
    @PostMapping("/user/mute")
    public Result muteUserGlobally(@RequestParam Long userId,
                                   @RequestParam String muteStart,
                                   @RequestParam String muteEnd) {
        requirePermission(PermissionCode.USER_DISABLE);
        boolean success = adminOperationService.setMutePeriod(userId, muteStart, muteEnd);
        return success ? Result.success("禁言时段设置成功") : Result.error("操作失败");
    }

    /** Default account removal is a reversible-data-safe archive, not history deletion. */
    @DeleteMapping("/user/{userId}")
    public Result deleteUser(@PathVariable Long userId) {
        requirePermission(PermissionCode.USER_DELETE);
        try {
            adminOperationService.archiveUser(userId, UserContextHolder.getCurrentUserId());
            return Result.success("用户已归档，历史消息与广播回执已保留");
        } catch (IllegalArgumentException e) {
            int code = "用户不存在".equals(e.getMessage()) ? 404 : 400;
            return Result.error(code, e.getMessage());
        }
    }

    /**
     * Separate high-risk erasure path. The exact phrase is "ERASE USER {userId}";
     * the account must already have been archived.
     */
    @PostMapping("/user/{userId}/physical-erasure")
    public Result<Void> physicallyEraseUser(@PathVariable Long userId,
                                            @RequestBody AdminPhysicalErasureDTO dto) {
        requirePermission(PermissionCode.USER_DELETE);
        try {
            String phrase = dto == null ? null : dto.getConfirmationPhrase();
            String reason = dto == null ? null : dto.getReason();
            adminOperationService.physicallyEraseUser(
                    userId,
                    UserContextHolder.getCurrentUserId(),
                    phrase,
                    reason);
            return Result.success();
        } catch (IllegalArgumentException e) {
            int code = "用户不存在".equals(e.getMessage())
                    ? 404
                    : e.getMessage() != null && e.getMessage().contains("必须先归档") ? 409 : 400;
            return Result.error(code, e.getMessage());
        }
    }

    /** Reset a regular account password and revoke all of its active sessions. */
    @PutMapping("/user/{userId}/password")
    public Result<Void> resetUserPassword(@PathVariable Long userId,
                                          @RequestBody AdminResetPasswordDTO dto) {
        requirePermission(PermissionCode.USER_PASSWORD_RESET);
        try {
            String newPassword = dto == null ? null : dto.getNewPassword();
            boolean success = adminOperationService.resetPassword(userId, newPassword);
            if (!success) return Result.error("密码重置失败");
            log.info("Administrator {} reset the password for account id {}",
                    UserContextHolder.getCurrentUser().getUsername(), userId);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 授予或撤销普通账号的广播发布权限。 */
    @PutMapping("/user/{userId}/broadcast-permission")
    public Result<Void> setBroadcastPermission(@PathVariable Long userId,
                                               @RequestParam boolean enabled) {
        requirePermission(PermissionCode.BROADCAST_PERMISSION_UPDATE);
        try {
            if (!adminOperationService.setBroadcastPermission(userId, enabled)) {
                return Result.error("广播权限更新失败");
            }
            if (webSocketHandler != null) {
                webSocketHandler.sendBroadcastPermissionUpdated(userId, enabled);
            }
            log.info("Administrator {} {} broadcast permission for account id {}",
                    UserContextHolder.getCurrentUser().getUsername(),
                    enabled ? "granted" : "revoked",
                    userId);
            return Result.success();
        } catch (IllegalArgumentException exception) {
            int code = "用户不存在".equals(exception.getMessage()) ? 404 : 400;
            return Result.error(code, exception.getMessage());
        }
    }

    /** Detailed dependency, storage, JVM and WebSocket diagnostics. */
    @GetMapping("/diagnostics")
    public Result<AdminDiagnostics> diagnostics() {
        requirePermission(PermissionCode.DIAGNOSTICS_READ);
        return Result.success(nodeDiagnosticsService.adminDiagnostics());
    }

    /** Bounded and parsed view of the active process log. */
    @GetMapping("/logs")
    public Result<RuntimeLogSnapshot> runtimeLogs(
            @RequestParam(defaultValue = "300") int limit,
            @RequestParam(defaultValue = "ALL") String level,
            @RequestParam(defaultValue = "") String keyword) {
        requirePermission(PermissionCode.RUNTIME_LOG_READ);
        return Result.success(runtimeLogService.read(limit, level, keyword));
    }

    /** Streams the complete active log file without accepting a client-controlled path. */
    @GetMapping("/logs/export")
    public ResponseEntity<Resource> exportRuntimeLog() {
        requirePermission(PermissionCode.RUNTIME_LOG_READ);
        var exported = runtimeLogService.openExport();
        if (exported.isEmpty()) return ResponseEntity.notFound().build();

        controlAuditService.appendRequired(
                UserContextHolder.getCurrentUserId(),
                "RUNTIME_LOG_EXPORTED",
                "CONTROL_SERVER",
                null,
                "SUCCEEDED",
                Map.of());

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
