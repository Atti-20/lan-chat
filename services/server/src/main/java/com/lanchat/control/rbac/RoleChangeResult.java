package com.lanchat.control.rbac;

public record RoleChangeResult(
        Long userId,
        String roleCode,
        boolean enabled,
        boolean changed
) { }
