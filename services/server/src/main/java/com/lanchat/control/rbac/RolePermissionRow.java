package com.lanchat.control.rbac;

import lombok.Data;

@Data
public class RolePermissionRow {
    private Long roleId;
    private String roleCode;
    private String roleName;
    private String permissionCode;
    private String riskLevel;
}
