package com.lanchat.control.device;

import java.time.LocalDateTime;

public record DeviceCredentialView(
        String credentialId,
        String algorithm,
        String fingerprint,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt,
        String certificatePayload,
        String certificateSignature,
        String controlSigningPublicKey,
        String controlKeyFingerprint
) { }
