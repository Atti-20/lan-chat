package com.lanchat.control.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceRecord {
    private Long id;
    private Long organizationId;
    private Long memberId;
    private Long ownerUserId;
    private String deviceKey;
    private String platform;
    private String displayName;
    private String appVersion;
    private String capabilitiesJson;
    private String status;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String rejectionReason;
    private Long revokedBy;
    private LocalDateTime revokedAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
