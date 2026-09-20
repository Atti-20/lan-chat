package com.lanchat.common;

import org.springframework.util.StringUtils;

import java.util.List;

/** Exact device sessions revoked by a committed account/session lifecycle change. */
public record DeviceSessionsRevokedEvent(
        Long userId,
        List<Long> deviceIds,
        String reason,
        String message
) {
    public DeviceSessionsRevokedEvent {
        deviceIds = deviceIds == null
                ? List.of()
                : deviceIds.stream()
                        .filter(id -> id != null && id > 0)
                        .distinct()
                        .toList();
        reason = StringUtils.hasText(reason) ? reason : "SESSION_REVOKED";
        message = StringUtils.hasText(message) ? message : "设备会话已经失效";
    }
}
