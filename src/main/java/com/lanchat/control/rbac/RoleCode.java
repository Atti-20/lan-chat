package com.lanchat.control.rbac;

import org.springframework.util.StringUtils;

import java.util.Locale;

public enum RoleCode {
    ORG_OWNER,
    ORG_ADMIN,
    SECURITY_ADMIN,
    DEVICE_ADMIN,
    DEPARTMENT_ADMIN,
    AUDITOR,
    MEMBER;

    public static RoleCode parse(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("角色代码不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知角色代码：" + value);
        }
    }
}
