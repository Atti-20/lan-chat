package com.lanchat.control.policy;

import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.control.device.ControlDeviceMapper;
import com.lanchat.control.device.DevicePolicyRecord;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.control.rbac.PermissionCode;
import com.lanchat.security.UserContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class OrganizationPolicyService {

    private static final Set<String> APPROVAL_MODES = Set.of("MANUAL", "AUTO");

    private final ControlDeviceMapper deviceMapper;
    private final AuthorizationService authorizationService;
    private final ControlServerProperties controlProperties;
    private final ControlAuditService auditService;

    public OrganizationPolicyService(ControlDeviceMapper deviceMapper,
                                     AuthorizationService authorizationService,
                                     ControlServerProperties controlProperties,
                                     ControlAuditService auditService) {
        this.deviceMapper = deviceMapper;
        this.authorizationService = authorizationService;
        this.controlProperties = controlProperties;
        this.auditService = auditService;
    }

    public OrganizationPolicyView current() {
        authorizationService.requireCurrentUserPermission(PermissionCode.POLICY_UPDATE);
        return view(requiredPolicy());
    }

    @Transactional
    public OrganizationPolicyView updateDevicePolicy(DevicePolicyUpdateRequest request) {
        authorizationService.requireCurrentUserPermission(PermissionCode.POLICY_UPDATE);
        if (request == null) throw new IllegalArgumentException("组织策略参数不能为空");
        Long expectedVersion = request.getExpectedVersion();
        if (expectedVersion == null || expectedVersion <= 0) {
            throw new IllegalArgumentException("expectedVersion 必须为正整数");
        }
        String expectedPhrase = "UPDATE DEVICE POLICY TO " + (expectedVersion + 1);
        if (!expectedPhrase.equals(request.getConfirmationPhrase())) {
            throw new IllegalArgumentException("确认短语不匹配，应为：" + expectedPhrase);
        }
        String approvalMode = request.getDeviceApprovalMode() == null
                ? "" : request.getDeviceApprovalMode().trim().toUpperCase(Locale.ROOT);
        if (!APPROVAL_MODES.contains(approvalMode)) {
            throw new IllegalArgumentException("deviceApprovalMode 只能为 MANUAL 或 AUTO");
        }
        int validityDays = bounded(
                request.getCredentialValidityDays(), 1, 3650, "credentialValidityDays");
        int maxOfflineHours = bounded(
                request.getMaxOfflineHours(), 1, 8760, "maxOfflineHours");
        if ((long) maxOfflineHours > (long) validityDays * 24L) {
            throw new IllegalArgumentException("maxOfflineHours 不能超过凭据总有效期");
        }

        DevicePolicyRecord before = requiredPolicy();
        if (!expectedVersion.equals(before.getVersion())) {
            throw new PolicyVersionConflictException("组织策略版本已经变化，请刷新后重试");
        }
        Long actorUserId = UserContextHolder.getCurrentUserId();
        if (deviceMapper.updateDevicePolicy(
                organizationKey(), expectedVersion, approvalMode,
                validityDays, maxOfflineHours, actorUserId) != 1) {
            throw new PolicyVersionConflictException("组织策略版本冲突，请刷新后重试");
        }
        auditService.appendRequired(
                actorUserId, "DEVICE_POLICY_UPDATED", "ORGANIZATION", organizationKey(),
                "SUCCEEDED",
                Map.of("previousVersion", expectedVersion,
                        "version", expectedVersion + 1,
                        "deviceApprovalMode", approvalMode,
                        "credentialValidityDays", validityDays,
                        "maxOfflineHours", maxOfflineHours));
        return view(requiredPolicy());
    }

    private int bounded(Integer value, int minimum, int maximum, String label) {
        if (value == null || value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    label + " 必须在 " + minimum + "-" + maximum + " 之间");
        }
        return value;
    }

    private DevicePolicyRecord requiredPolicy() {
        DevicePolicyRecord policy = deviceMapper.selectPolicy(organizationKey());
        if (policy == null) throw new IllegalStateException("组织策略未初始化");
        return policy;
    }

    private OrganizationPolicyView view(DevicePolicyRecord policy) {
        return new OrganizationPolicyView(
                organizationKey(), policy.getRegistrationMode(),
                Boolean.TRUE.equals(policy.getP2pEnabled()), policy.getDeviceApprovalMode(),
                policy.getCredentialValidityDays() == null ? 90 : policy.getCredentialValidityDays(),
                policy.getMaxOfflineHours() == null ? 72 : policy.getMaxOfflineHours(),
                policy.getRevocationVersion() == null ? 0 : policy.getRevocationVersion(),
                policy.getVersion() == null ? 0 : policy.getVersion(),
                policy.getUpdatedBy(), policy.getUpdatedAt());
    }

    private String organizationKey() {
        return controlProperties.resolvedOrganizationId();
    }
}
