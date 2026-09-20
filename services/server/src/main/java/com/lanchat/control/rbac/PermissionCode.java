package com.lanchat.control.rbac;

/** Stable server-side authorization codes. UI visibility must never replace these checks. */
public enum PermissionCode {
    USER_READ,
    USER_CREATE,
    USER_DISABLE,
    USER_DELETE,
    USER_PASSWORD_RESET,
    BROADCAST_CREATE,
    BROADCAST_ALL,
    BROADCAST_PERMISSION_UPDATE,
    DIAGNOSTICS_READ,
    RUNTIME_LOG_READ,
    DEVICE_APPROVE,
    DEVICE_REVOKE,
    ROLE_ASSIGN,
    POLICY_UPDATE,
    AUDIT_READ,
    LICENSE_READ,
    AI_TOOL_EXECUTE,
    AI_TOOL_APPROVE
}
