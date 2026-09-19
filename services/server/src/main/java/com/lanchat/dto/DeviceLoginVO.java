package com.lanchat.dto;

import com.lanchat.entity.DeviceLogin;

import java.time.LocalDateTime;

public record DeviceLoginVO(
        Long id,
        Long userId,
        String deviceType,
        String deviceName,
        LocalDateTime loginTime,
        LocalDateTime expireTime,
        Integer status,
        boolean current
) {
    public static DeviceLoginVO from(DeviceLogin device, boolean current) {
        return new DeviceLoginVO(
                device.getId(),
                device.getUserId(),
                device.getDeviceType(),
                device.getDeviceName(),
                device.getLoginTime(),
                device.getExpireTime(),
                device.getStatus(),
                current
        );
    }
}
