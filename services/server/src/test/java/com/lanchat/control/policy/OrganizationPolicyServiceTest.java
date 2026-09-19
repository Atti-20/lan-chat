package com.lanchat.control.policy;

import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.control.device.ControlDeviceMapper;
import com.lanchat.control.device.DevicePolicyRecord;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.control.rbac.PermissionCode;
import com.lanchat.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationPolicyServiceTest {

    private ControlDeviceMapper deviceMapper;
    private AuthorizationService authorizationService;
    private ControlAuditService auditService;
    private OrganizationPolicyService service;

    @BeforeEach
    void setUp() {
        deviceMapper = mock(ControlDeviceMapper.class);
        authorizationService = mock(AuthorizationService.class);
        auditService = mock(ControlAuditService.class);
        ControlServerProperties properties = new ControlServerProperties();
        properties.setOrganizationId("org-test");
        service = new OrganizationPolicyService(
                deviceMapper, authorizationService, properties, auditService);
        LoginUser actor = new LoginUser(7L, "owner.account", "web", "token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentPolicyRequiresCriticalPolicyPermission() {
        when(deviceMapper.selectPolicy("org-test")).thenReturn(policy(3L, "MANUAL", 90, 72));

        OrganizationPolicyView view = service.current();

        assertEquals(3L, view.version());
        assertEquals("MANUAL", view.deviceApprovalMode());
        verify(authorizationService).requireCurrentUserPermission(PermissionCode.POLICY_UPDATE);
    }

    @Test
    void updateUsesExactPhraseOptimisticVersionAndAudit() {
        DevicePolicyRecord before = policy(3L, "MANUAL", 90, 72);
        DevicePolicyRecord after = policy(4L, "AUTO", 30, 48);
        when(deviceMapper.selectPolicy("org-test")).thenReturn(before, after);
        when(deviceMapper.updateDevicePolicy("org-test", 3L, "AUTO", 30, 48, 7L))
                .thenReturn(1);
        DevicePolicyUpdateRequest request = request(3L, "auto", 30, 48,
                "UPDATE DEVICE POLICY TO 4");

        OrganizationPolicyView result = service.updateDevicePolicy(request);

        assertEquals(4L, result.version());
        assertEquals("AUTO", result.deviceApprovalMode());
        verify(auditService).appendRequired(
                eq(7L), eq("DEVICE_POLICY_UPDATED"), eq("ORGANIZATION"), eq("org-test"),
                eq("SUCCEEDED"), any());
    }

    @Test
    void wrongPhraseNeverMutatesPolicy() {
        DevicePolicyUpdateRequest request = request(3L, "MANUAL", 90, 72, "wrong");

        assertThrows(IllegalArgumentException.class,
                () -> service.updateDevicePolicy(request));

        verify(deviceMapper, never()).updateDevicePolicy(
                any(), any(), any(), any(Integer.class), any(Integer.class), any());
    }

    @Test
    void concurrentVersionChangeReturnsConflictAndNoSuccessAudit() {
        when(deviceMapper.selectPolicy("org-test")).thenReturn(policy(4L, "MANUAL", 90, 72));
        DevicePolicyUpdateRequest request = request(
                3L, "MANUAL", 90, 72, "UPDATE DEVICE POLICY TO 4");

        assertThrows(PolicyVersionConflictException.class,
                () -> service.updateDevicePolicy(request));

        verify(deviceMapper, never()).updateDevicePolicy(
                any(), any(), any(), any(Integer.class), any(Integer.class), any());
        verify(auditService, never()).appendRequired(any(), any(), any(), any(), any(), any());
    }

    @Test
    void offlineWindowCannotOutliveCredential() {
        when(deviceMapper.selectPolicy("org-test")).thenReturn(policy(3L, "MANUAL", 90, 72));
        DevicePolicyUpdateRequest request = request(
                3L, "MANUAL", 1, 25, "UPDATE DEVICE POLICY TO 4");

        assertThrows(IllegalArgumentException.class,
                () -> service.updateDevicePolicy(request));
    }

    private DevicePolicyUpdateRequest request(Long version,
                                              String mode,
                                              int validityDays,
                                              int offlineHours,
                                              String phrase) {
        DevicePolicyUpdateRequest request = new DevicePolicyUpdateRequest();
        request.setExpectedVersion(version);
        request.setDeviceApprovalMode(mode);
        request.setCredentialValidityDays(validityDays);
        request.setMaxOfflineHours(offlineHours);
        request.setConfirmationPhrase(phrase);
        return request;
    }

    private DevicePolicyRecord policy(Long version,
                                      String mode,
                                      int validityDays,
                                      int offlineHours) {
        DevicePolicyRecord policy = new DevicePolicyRecord();
        policy.setRegistrationMode("ADMIN_CREATED");
        policy.setP2pEnabled(false);
        policy.setDeviceApprovalMode(mode);
        policy.setCredentialValidityDays(validityDays);
        policy.setMaxOfflineHours(offlineHours);
        policy.setRevocationVersion(2L);
        policy.setVersion(version);
        return policy;
    }
}
