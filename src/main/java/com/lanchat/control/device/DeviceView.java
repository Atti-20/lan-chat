package com.lanchat.control.device;

import java.time.LocalDateTime;
import java.util.List;

public record DeviceView(
        Long id,
        Long ownerUserId,
        String deviceKey,
        String platform,
        String displayName,
        String appVersion,
        List<String> capabilities,
        String status,
        LocalDateTime approvedAt,
        String rejectionReason,
        LocalDateTime revokedAt,
        LocalDateTime lastSeenAt,
        DeviceCredentialView credential
) { }
