package com.lanchat.control.api;

import com.lanchat.common.Result;
import com.lanchat.control.service.ControlServerInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public V2 control-plane handshake endpoints. V1 node endpoints remain compatible. */
@RestController
@RequestMapping("/api/v2/control")
public class ControlInfoController {

    private final ControlServerInfoService infoService;

    public ControlInfoController(ControlServerInfoService infoService) {
        this.infoService = infoService;
    }

    @GetMapping("/info")
    public Result<ControlInfo> info() {
        return Result.success(infoService.publicInfo());
    }

    @GetMapping("/health")
    public Result<ControlHealth> health() {
        return Result.success(infoService.publicHealth());
    }
}
