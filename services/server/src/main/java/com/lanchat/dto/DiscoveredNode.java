package com.lanchat.dto;

import java.time.Instant;

/** Sanitized LAN node announced through DNS-SD. */
public record DiscoveredNode(
        String nodeId,
        String nodeName,
        String organizationName,
        String version,
        String mode,
        String appUrl,
        boolean secure,
        boolean current,
        Instant lastSeenAt
) {
}
