package com.lanchat.control.device;

import com.lanchat.common.Result;
import com.lanchat.control.audit.ControlAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/control/devices")
public class ControlDeviceController {

    private final DeviceManagementService deviceManagementService;
    private final ControlAuditService auditService;

    public ControlDeviceController(DeviceManagementService deviceManagementService,
                                   ControlAuditService auditService) {
        this.deviceManagementService = deviceManagementService;
        this.auditService = auditService;
    }

    @PostMapping("/registrations")
    public Result<DeviceView> register(@RequestBody DeviceRegistrationRequest request) {
        String target = request == null ? null : request.getDeviceKey();
        try {
            return Result.success(deviceManagementService.register(request));
        } catch (RuntimeException exception) {
            auditService.appendDeniedSafely(
                    "DEVICE_REGISTRATION_DENIED", "DEVICE_KEY", target, Map.of());
            throw exception;
        }
    }

    @GetMapping
    public Result<List<DeviceView>> list(
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(deviceManagementService.list(limit));
    }

    @PostMapping("/{deviceId}/approve")
    public Result<DeviceView> approve(@PathVariable Long deviceId) {
        try {
            return Result.success(deviceManagementService.approve(deviceId));
        } catch (RuntimeException exception) {
            denied("DEVICE_APPROVAL_DENIED", deviceId);
            throw exception;
        }
    }

    @PostMapping("/{deviceId}/reject")
    public Result<DeviceView> reject(@PathVariable Long deviceId,
                                     @RequestBody DeviceRejectionRequest request) {
        try {
            return Result.success(deviceManagementService.reject(deviceId, request));
        } catch (RuntimeException exception) {
            denied("DEVICE_REJECTION_DENIED", deviceId);
            throw exception;
        }
    }

    @PostMapping("/{deviceId}/revoke")
    public Result<DeviceView> revoke(@PathVariable Long deviceId,
                                     @RequestBody DeviceRevocationRequest request) {
        try {
            return Result.success(deviceManagementService.revoke(deviceId, request));
        } catch (RuntimeException exception) {
            denied("DEVICE_REVOCATION_DENIED", deviceId);
            throw exception;
        }
    }

    @GetMapping("/revocations")
    public Result<RevocationSnapshotView> revocations(
            @RequestParam(defaultValue = "0") long afterVersion,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(deviceManagementService.revocations(afterVersion, limit));
    }

    private void denied(String action, Long deviceId) {
        auditService.appendDeniedSafely(
                action, "DEVICE", deviceId == null ? null : String.valueOf(deviceId), Map.of());
    }
}
