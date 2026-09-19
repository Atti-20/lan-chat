package com.lanchat.control.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RevocationEntryRecord {
    private Long id;
    private Long organizationId;
    private String subjectType;
    private String subjectId;
    private Long version;
    private String reason;
    private String signedPayload;
    private String signature;
    private String controlKeyFingerprint;
    private Long revokedBy;
    private LocalDateTime revokedAt;
    private LocalDateTime expiresAt;
}
