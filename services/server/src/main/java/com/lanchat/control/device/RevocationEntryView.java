package com.lanchat.control.device;

import java.time.LocalDateTime;

public record RevocationEntryView(
        long version,
        String subjectType,
        String subjectId,
        String reason,
        LocalDateTime revokedAt,
        LocalDateTime expiresAt,
        String signedPayload,
        String signature,
        String controlKeyFingerprint
) { }
