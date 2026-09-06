package com.lanchat.control.policy;

import com.lanchat.common.Result;
import com.lanchat.control.audit.ControlAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/control/policy")
public class OrganizationPolicyController {

    private final OrganizationPolicyService policyService;
    private final ControlAuditService auditService;

    public OrganizationPolicyController(OrganizationPolicyService policyService,
                                        ControlAuditService auditService) {
        this.policyService = policyService;
        this.auditService = auditService;
    }

    @GetMapping
    public Result<OrganizationPolicyView> current() {
        return Result.success(policyService.current());
    }

    @PutMapping("/device-identity")
    public Result<OrganizationPolicyView> updateDevicePolicy(
            @RequestBody DevicePolicyUpdateRequest request) {
        try {
            return Result.success(policyService.updateDevicePolicy(request));
        } catch (RuntimeException exception) {
            auditService.appendDeniedSafely(
                    "DEVICE_POLICY_UPDATE_DENIED", "ORGANIZATION", null, Map.of());
            throw exception;
        }
    }
}
