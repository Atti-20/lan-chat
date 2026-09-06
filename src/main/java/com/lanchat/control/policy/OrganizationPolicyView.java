package com.lanchat.control.policy;

import java.time.LocalDateTime;

public record OrganizationPolicyView(
        String organizationId,
        String registrationMode,
        boolean p2pEnabled,
        String deviceApprovalMode,
        int credentialValidityDays,
        int maxOfflineHours,
        long revocationVersion,
        long version,
        Long updatedBy,
        LocalDateTime updatedAt
) { }
