package com.lanchat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Public, non-secret identity and operating policy of this LanChat node. */
@Data
@Component
@ConfigurationProperties(prefix = "lanchat.node")
public class LanChatNodeProperties {

    private String id = "";
    private String name = "LanChat Node";
    private String organizationName = "Local Organization";
    private String mode = "LAN_FIRST";
    private String version = "2.1.0";
    private String advertisedHost = "";
    private int advertisedPort = 8080;
    private boolean secure;

    public String resolvedId() {
        if (StringUtils.hasText(id)) {
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            if (normalized.matches("^[a-z0-9][a-z0-9_-]{2,63}$")) return normalized;
        }
        String host = "localhost";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // The generated UUID remains deterministic within a stable hostname.
        }
        return "node-" + UUID.nameUUIDFromBytes(
                (host + ":" + name).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 16);
    }

    public String normalizedMode() {
        if (!StringUtils.hasText(mode)) return "LAN_FIRST";
        String normalized = mode.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "LOCAL_INDEPENDENT", "LAN_FIRST", "HYBRID" -> normalized;
            default -> "LAN_FIRST";
        };
    }
}
