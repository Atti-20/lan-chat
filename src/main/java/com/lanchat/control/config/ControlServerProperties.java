package com.lanchat.control.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Public identity of the centralized MeshX control plane. */
@Data
@Component
@ConfigurationProperties(prefix = "meshx.control")
public class ControlServerProperties {

    private String id = "";
    private String organizationId = "org-local";

    public String resolvedId() {
        String configured = normalizedIdentifier(id);
        if (configured != null) return configured;

        String host = "localhost";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // The organization still provides a stable deterministic fallback.
        }
        return "control-" + UUID.nameUUIDFromBytes(
                        (host + ":" + resolvedOrganizationId()).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 16);
    }

    public String resolvedOrganizationId() {
        String configured = normalizedIdentifier(organizationId);
        return configured == null ? "org-local" : configured;
    }

    private String normalizedIdentifier(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-z0-9][a-z0-9_-]{2,63}$") ? normalized : null;
    }
}
