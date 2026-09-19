package com.lanchat.control.device;

import com.lanchat.control.rbac.PermissionCode;

import java.util.List;

public record ControlSessionView(
        String organizationId,
        Long userId,
        Long memberId,
        String memberStatus,
        List<String> roles,
        List<PermissionCode> permissions,
        DeviceView device,
        long revocationVersion
) { }
