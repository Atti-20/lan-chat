package com.lanchat.control.policy;

import com.lanchat.control.audit.ControlAuditService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationPolicyControllerTest {

    @Test
    void currentReturnsServiceSnapshot() {
        OrganizationPolicyService service = mock(OrganizationPolicyService.class);
        ControlAuditService audit = mock(ControlAuditService.class);
        OrganizationPolicyView view = new OrganizationPolicyView(
                "org-test", "ADMIN_CREATED", false, "MANUAL",
                90, 72, 2L, 3L, 7L, null);
        when(service.current()).thenReturn(view);

        var result = new OrganizationPolicyController(service, audit).current();

        assertEquals(view, result.getData());
    }

    @Test
    void deniedUpdateAttemptsSeparateAudit() {
        OrganizationPolicyService service = mock(OrganizationPolicyService.class);
        ControlAuditService audit = mock(ControlAuditService.class);
        DevicePolicyUpdateRequest request = new DevicePolicyUpdateRequest();
        doThrow(new IllegalArgumentException("bad phrase"))
                .when(service).updateDevicePolicy(request);
        OrganizationPolicyController controller = new OrganizationPolicyController(service, audit);

        assertThrows(IllegalArgumentException.class,
                () -> controller.updateDevicePolicy(request));

        verify(audit).appendDeniedSafely(
                "DEVICE_POLICY_UPDATE_DENIED", "ORGANIZATION", null, Map.of());
    }
}
