package com.lanchat.control.policy;

import lombok.Data;

@Data
public class DevicePolicyUpdateRequest {
    private Long expectedVersion;
    private String deviceApprovalMode;
    private Integer credentialValidityDays;
    private Integer maxOfflineHours;
    private String confirmationPhrase;
}
