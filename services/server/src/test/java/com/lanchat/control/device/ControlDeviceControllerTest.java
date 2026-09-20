package com.lanchat.control.device;

import com.lanchat.control.audit.ControlAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlDeviceControllerTest {

    private DeviceManagementService deviceManagementService;
    private ControlAuditService auditService;
    private ControlDeviceController controller;

    @BeforeEach
    void setUp() {
        deviceManagementService = mock(DeviceManagementService.class);
        auditService = mock(ControlAuditService.class);
        controller = new ControlDeviceController(deviceManagementService, auditService);
    }

    @Test
    void registrationReturnsIssuedServiceView() {
        DeviceRegistrationRequest request = new DeviceRegistrationRequest();
        request.setDeviceKey("device-0001");
        DeviceView view = new DeviceView(
                5L, 7L, "device-0001", "DESKTOP", "Office Mac", "0.3.0",
                java.util.List.of("CHAT"), "PENDING", null, null, null, null, null);
        when(deviceManagementService.register(request)).thenReturn(view);

        var result = controller.register(request);

        assertEquals(200, result.getCode());
        assertEquals(view, result.getData());
    }

    @Test
    void deniedApprovalWritesSeparateDeniedAudit() {
        doThrow(new AccessDeniedException("forbidden"))
                .when(deviceManagementService).approve(5L);

        assertThrows(AccessDeniedException.class, () -> controller.approve(5L));

        verify(auditService).appendDeniedSafely(
                eq("DEVICE_APPROVAL_DENIED"), eq("DEVICE"), eq("5"), eq(Map.of()));
    }

    @Test
    void revocationRequiresServiceConfirmationAndAuditsDenial() {
        DeviceRevocationRequest request = new DeviceRevocationRequest();
        request.setConfirmationPhrase("wrong");
        request.setReason("LOST_DEVICE");
        doThrow(new IllegalArgumentException("confirmation"))
                .when(deviceManagementService).revoke(5L, request);

        assertThrows(IllegalArgumentException.class, () -> controller.revoke(5L, request));

        verify(auditService).appendDeniedSafely(
                "DEVICE_REVOCATION_DENIED", "DEVICE", "5", Map.of());
    }
}
