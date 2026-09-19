package com.lanchat.control.api;

import java.util.List;

/** Sanitized control-plane metadata available before authentication. */
public record ControlInfo(
        String controlId,
        String controlName,
        String organizationId,
        String organizationName,
        String mode,
        String serviceStatus,
        int protocolVersion,
        List<String> authMethods,
        boolean secure,
        List<String> features,
        String version,
        String apiBasePath,
        String webSocketPath,
        String healthPath,
        String appPath,
        boolean desktopAuthSupported,
        String refreshTransport,
        String legacyApiBasePath,
        boolean deviceIdentityEnabled,
        String controlSigningPublicKey,
        String controlSigningKeyFingerprint,
        long serverTime
) {
}
