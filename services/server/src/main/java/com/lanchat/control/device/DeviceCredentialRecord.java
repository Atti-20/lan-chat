package com.lanchat.control.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceCredentialRecord {
    private Long id;
    private Long deviceId;
    private String credentialId;
    private String algorithm;
    private String publicKey;
    private String fingerprint;
    private String status;
    private String certificatePayload;
    private String certificateSignature;
    private String controlKeyFingerprint;
    private LocalDateTime requestedAt;
    private Long approvedBy;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
}
