package com.lanchat.control.rbac;

import com.lanchat.common.Result;
import com.lanchat.control.audit.ControlAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/control/rbac")
public class ControlRbacController {

    private final RoleManagementService roleManagementService;
    private final ControlAuditService auditService;

    public ControlRbacController(RoleManagementService roleManagementService,
                                 ControlAuditService auditService) {
        this.roleManagementService = roleManagementService;
        this.auditService = auditService;
    }

    @GetMapping("/roles")
    public Result<List<RoleView>> roles() {
        return Result.success(roleManagementService.listRoles());
    }

    @GetMapping("/members/{userId}/roles")
    public Result<MemberRolesView> memberRoles(@PathVariable Long userId) {
        return Result.success(roleManagementService.memberRoles(userId));
    }

    @PutMapping("/members/{userId}/roles/{roleCode}")
    public Result<RoleChangeResult> changeRole(@PathVariable Long userId,
                                               @PathVariable String roleCode,
                                               @RequestBody RoleAssignmentRequest request) {
        try {
            if (request == null || request.getEnabled() == null) {
                throw new IllegalArgumentException("enabled 不能为空");
            }
            return Result.success(roleManagementService.changeRole(
                    userId, roleCode, request.getEnabled()));
        } catch (RuntimeException exception) {
            auditService.appendDeniedSafely(
                    "ROLE_CHANGE_DENIED",
                    "USER",
                    String.valueOf(userId),
                    Map.of("roleCode", roleCode == null ? "" : roleCode));
            throw exception;
        }
    }

    @PostMapping("/owner-transfer")
    public Result<MemberRolesView> transferOwner(@RequestBody OwnerTransferRequest request) {
        Long targetUserId = request == null ? null : request.getTargetUserId();
        try {
            if (request == null) throw new IllegalArgumentException("所有者转移参数不能为空");
            return Result.success(roleManagementService.transferOwner(
                    request.getTargetUserId(), request.getConfirmationPhrase()));
        } catch (RuntimeException exception) {
            auditService.appendDeniedSafely(
                    "ORG_OWNER_TRANSFER_DENIED",
                    "USER",
                    targetUserId == null ? null : String.valueOf(targetUserId),
                    Map.of());
            throw exception;
        }
    }
}
