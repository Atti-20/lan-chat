package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.NodePublicInfo;
import com.lanchat.dto.DiscoveredNode;
import com.lanchat.service.LanNodeDiscoveryService;
import com.lanchat.service.NodeDiagnosticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

/** Public node handshake endpoints with an intentionally small disclosure surface. */
@RestController
@RequestMapping("/api/v1/node")
public class NodeController {

    private final NodeDiagnosticsService diagnosticsService;
    private final LanNodeDiscoveryService discoveryService;

    public NodeController(NodeDiagnosticsService diagnosticsService,
                          LanNodeDiscoveryService discoveryService) {
        this.diagnosticsService = diagnosticsService;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/info")
    public Result<NodePublicInfo> info() {
        return Result.success(diagnosticsService.publicInfo());
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.success(diagnosticsService.publicHealth());
    }

    @GetMapping("/discoveries")
    public Result<List<DiscoveredNode>> discoveries() {
        return Result.success(discoveryService.listDiscoveredNodes());
    }
}
