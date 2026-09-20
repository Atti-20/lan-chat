package com.lanchat.control.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceSessionRecord {
    private Long id;
    private Long deviceId;
    private Long userId;
    private Long legacyDeviceLoginId;
    private String status;
    private LocalDateTime expiresAt;
}
