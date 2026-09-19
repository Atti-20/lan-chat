package com.lanchat.control.audit;

import com.lanchat.common.Result;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.control.rbac.PermissionCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({"/api/v2/control/audit", "/api/v1/admin/audit"})
public class ControlAuditController {

    private final AuthorizationService authorizationService;
    private final ControlAuditService auditService;

    public ControlAuditController(AuthorizationService authorizationService,
                                  ControlAuditService auditService) {
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @GetMapping
    public Result<List<ControlAuditEventView>> recent(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome) {
        authorizationService.requireCurrentUserPermission(PermissionCode.AUDIT_READ);
        return Result.success(auditService.recent(action, outcome, limit));
    }
}
