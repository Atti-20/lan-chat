package com.lanchat.control.rbac;

import lombok.Data;

@Data
public class OrganizationMemberRow {
    private Long memberId;
    private Long userId;
    private String status;
}
