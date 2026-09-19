package com.lanchat.control.rbac;

import java.util.List;

public record MemberRolesView(
        Long userId,
        Long memberId,
        String status,
        List<String> roles,
        List<PermissionCode> permissions
) { }
