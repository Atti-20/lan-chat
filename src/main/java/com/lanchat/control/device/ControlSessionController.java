package com.lanchat.control.device;

import com.lanchat.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/control/session")
public class ControlSessionController {

    private final DeviceManagementService deviceManagementService;

    public ControlSessionController(DeviceManagementService deviceManagementService) {
        this.deviceManagementService = deviceManagementService;
    }

    @GetMapping
    public Result<ControlSessionView> current() {
        return Result.success(deviceManagementService.currentSession());
    }
}
