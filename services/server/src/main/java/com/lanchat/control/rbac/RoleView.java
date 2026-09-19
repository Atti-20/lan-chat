package com.lanchat.control.rbac;

import java.util.List;

public record RoleView(
        String code,
        String name,
        boolean critical,
        List<String> permissions
) { }
