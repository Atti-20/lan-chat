package com.lanchat.control.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DevicePolicyRecord {
    private Long id;
    private Long organizationId;
    private String registrationMode;
    private Boolean p2pEnabled;
    private String deviceApprovalMode;
    private Integer credentialValidityDays;
    private Integer maxOfflineHours;
    private Long revocationVersion;
    private Long version;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
