package com.lanchat.control.service;

import com.lanchat.config.LanChatNodeProperties;
import com.lanchat.control.api.ControlHealth;
import com.lanchat.control.api.ControlInfo;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.control.device.DeviceSigningService;
import com.lanchat.control.protocol.ControlProtocol;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Builds the public V2 control-plane handshake without exposing private configuration. */
@Service
public class ControlServerInfoService {

    private final ControlServerProperties controlProperties;
    private final LanChatNodeProperties nodeProperties;
    private final DeviceSigningService deviceSigningService;
    private final Instant startedAt = Instant.now();

    public ControlServerInfoService(ControlServerProperties controlProperties,
                                    LanChatNodeProperties nodeProperties,
                                    DeviceSigningService deviceSigningService) {
        this.controlProperties = controlProperties;
        this.nodeProperties = nodeProperties;
        this.deviceSigningService = deviceSigningService;
    }

    public ControlInfo publicInfo() {
        return new ControlInfo(
                controlProperties.resolvedId(),
                safeLabel(nodeProperties.getName(), "MeshX Control"),
                controlProperties.resolvedOrganizationId(),
                safeLabel(nodeProperties.getOrganizationName(), "Local Organization"),
                nodeProperties.normalizedMode(),
                "AVAILABLE",
                ControlProtocol.PROTOCOL_VERSION,
                List.of("PASSWORD"),
                nodeProperties.isSecure(),
                deviceSigningService.isEnabled()
                        ? List.of("CHAT", "FILE", "NODE_MANAGEMENT", "DEVICE_IDENTITY")
                        : List.of("CHAT", "FILE", "NODE_MANAGEMENT"),
                safeLabel(nodeProperties.getVersion(), "unknown"),
                ControlProtocol.API_BASE_PATH,
                "/ws/chat",
                ControlProtocol.HEALTH_PATH,
                "/app/",
                true,
                "HTTP_ONLY_COOKIE",
                "/api/v1",
                deviceSigningService.isEnabled(),
                deviceSigningService.isEnabled() ? deviceSigningService.signingPublicKey() : null,
                deviceSigningService.isEnabled() ? deviceSigningService.signingKeyFingerprint() : null,
                System.currentTimeMillis()
        );
    }

    public ControlHealth publicHealth() {
        return new ControlHealth(
                "UP",
                controlProperties.resolvedId(),
                ControlProtocol.PROTOCOL_VERSION,
                safeLabel(nodeProperties.getVersion(), "unknown"),
                Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds()),
                System.currentTimeMillis()
        );
    }

    private String safeLabel(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String sanitized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (sanitized.isEmpty()) return fallback;
        return sanitized.substring(0, Math.min(80, sanitized.length()));
    }
}
