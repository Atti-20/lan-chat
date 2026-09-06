package com.lanchat.control.admin;

import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.device.DeviceManagementService;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.entity.User;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Transaction boundary shared by legacy administrator mutations and Control audit writes.
 */
@Service
public class ControlAdminOperationService {

    private final UserService userService;
    private final ControlAuditService auditService;
    private final DeviceManagementService deviceManagementService;

    public ControlAdminOperationService(UserService userService,
                                        ControlAuditService auditService,
                                        DeviceManagementService deviceManagementService) {
        this.userService = userService;
        this.auditService = auditService;
        this.deviceManagementService = deviceManagementService;
    }

    @Transactional
    public boolean createUser(RegisterDTO request) {
        boolean created = userService.register(request);
        if (created) audit("USER_CREATED", createdUserId(request), Map.of());
        return created;
    }

    @Transactional
    public boolean setStatus(Long userId, Integer status) {
        boolean changed = userService.setStatusByAdmin(userId, status);
        if (changed) {
            int revokedDevices = status != null && status == 0
                    ? deviceManagementService.revokeAllForUser(
                            userId, UserContextHolder.getCurrentUserId(), "ACCOUNT_DISABLED")
                    : 0;
            audit("USER_STATUS_CHANGED", userId,
                    Map.of("status", status, "revokedDevices", revokedDevices));
        }
        return changed;
    }

    @Transactional
    public boolean setMutePeriod(Long userId, String muteStart, String muteEnd) {
        boolean changed = userService.setMutePeriod(userId, muteStart, muteEnd);
        if (changed) audit("USER_MUTE_POLICY_UPDATED", userId, Map.of());
        return changed;
    }

    @Transactional
    public boolean archiveUser(Long userId, Long actorUserId) {
        boolean archived = userService.archiveUserByAdmin(userId, actorUserId);
        if (archived) {
            int revokedDevices = deviceManagementService.revokeAllForUser(
                    userId, actorUserId, "ACCOUNT_ARCHIVED");
            audit("USER_ARCHIVED", userId, Map.of("revokedDevices", revokedDevices));
        }
        return archived;
    }

    @Transactional
    public boolean physicallyEraseUser(Long userId,
                                       Long actorUserId,
                                       String confirmationPhrase,
                                       String reason) {
        boolean erased = userService.physicallyEraseUserByAdmin(
                userId, actorUserId, confirmationPhrase, reason);
        if (erased) audit("USER_PHYSICALLY_ERASED", userId, Map.of());
        return erased;
    }

    @Transactional
    public boolean resetPassword(Long userId, String newPassword) {
        boolean reset = userService.resetPasswordByAdmin(userId, newPassword);
        if (reset) audit("USER_PASSWORD_RESET", userId, Map.of());
        return reset;
    }

    @Transactional
    public boolean setBroadcastPermission(Long userId, boolean enabled) {
        boolean changed = userService.setBroadcastPermission(userId, enabled);
        if (changed) {
            audit("BROADCAST_PERMISSION_UPDATED", userId, Map.of("enabled", enabled));
        }
        return changed;
    }

    private void audit(String action, Long targetUserId, Map<String, ?> detail) {
        auditService.appendRequired(
                UserContextHolder.getCurrentUserId(),
                action,
                "USER",
                targetUserId == null ? null : String.valueOf(targetUserId),
                "SUCCEEDED",
                detail);
    }

    private Long createdUserId(RegisterDTO request) {
        if (request == null || request.getUsername() == null) return null;
        String username = request.getUsername().trim();
        List<User> matches = userService.searchUsers(username);
        if (matches == null) return null;
        return matches.stream()
                .filter(user -> user != null && username.equals(user.getUsername()))
                .map(User::getId)
                .findFirst()
                .orElse(null);
    }
}
