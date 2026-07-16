package com.lanchat.cluster;

import com.lanchat.config.LanChatNodeProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.Locale;
import java.util.UUID;

/** Runtime identity and Redis routing policy for replicas of one logical LanChat node. */
@Data
@Component
@ConfigurationProperties(prefix = "lanchat.cluster")
public class LanChatClusterProperties {

    private boolean enabled;
    private String id = "";
    private String instanceId = "";
    private String channel = "lanchat:{clusterId}:realtime:v1";
    private long presenceTtlSeconds = 90;
    private long presenceHeartbeatSeconds = 25;

    private volatile String generatedInstanceId;

    public String resolvedClusterId(LanChatNodeProperties nodeProperties) {
        String configured = normalizedIdentifier(id);
        return configured == null ? nodeProperties.resolvedId() : configured;
    }

    public String resolvedInstanceId() {
        String configured = normalizedIdentifier(instanceId);
        if (configured != null) return configured;
        String resolved = generatedInstanceId;
        if (resolved != null) return resolved;
        synchronized (this) {
            if (generatedInstanceId == null) {
                String host = "instance";
                try {
                    host = InetAddress.getLocalHost().getHostName();
                } catch (Exception ignored) {
                    // A random suffix still makes the process identity unique.
                }
                String safeHost = host.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_-]", "-")
                        .replaceAll("-+", "-");
                safeHost = safeHost.substring(0, Math.min(32, safeHost.length()));
                if (safeHost.length() < 3) safeHost = "instance";
                generatedInstanceId = safeHost + "-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            }
            return generatedInstanceId;
        }
    }

    public String resolvedChannel(LanChatNodeProperties nodeProperties) {
        String clusterId = resolvedClusterId(nodeProperties);
        String configured = StringUtils.hasText(channel)
                ? channel.trim() : "lanchat:{clusterId}:realtime:v1";
        configured = configured.replace("{clusterId}", clusterId);
        if (configured.length() > 160 || !configured.matches("^[A-Za-z0-9:{}_.-]+$")) {
            return "lanchat:{" + clusterId + "}:realtime:v1";
        }
        return configured;
    }

    public long effectivePresenceTtlSeconds() {
        return Math.max(30, Math.min(presenceTtlSeconds, 600));
    }

    public long effectivePresenceHeartbeatSeconds() {
        long maximum = Math.max(10, effectivePresenceTtlSeconds() / 2);
        return Math.max(5, Math.min(presenceHeartbeatSeconds, maximum));
    }

    private String normalizedIdentifier(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-z0-9][a-z0-9_-]{2,63}$") ? normalized : null;
    }
}
