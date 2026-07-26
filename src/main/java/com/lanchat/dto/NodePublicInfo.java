package com.lanchat.dto;

import java.util.Set;

/** Safe node metadata available before login. */
public record NodePublicInfo(
        String nodeId,
        String nodeName,
        String organizationName,
        String version,
        String mode,
        String serviceStatus,
        boolean secure,
        boolean discoveryEnabled,
        boolean selfRegistrationEnabled,
        Set<String> loginMethods,
        Set<String> capabilities,
        long serverTime,
        int protocolVersion,
        String apiBasePath,
        String webSocketPath,
        String healthPath,
        String appPath,
        boolean desktopAuthSupported,
        String refreshTransport
) {
}
