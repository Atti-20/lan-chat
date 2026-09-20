package com.lanchat.control.rbac;

import com.lanchat.control.audit.ControlAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlRbacControllerTest {

    private RoleManagementService roleManagementService;
    private ControlAuditService auditService;
    private ControlRbacController controller;

    @BeforeEach
    void setUp() {
        roleManagementService = mock(RoleManagementService.class);
        auditService = mock(ControlAuditService.class);
        controller = new ControlRbacController(roleManagementService, auditService);
    }

    @Test
    void roleChangeReturnsTheTransactionalServiceResult() {
        RoleAssignmentRequest request = new RoleAssignmentRequest();
        request.setEnabled(true);
        RoleChangeResult changed = new RoleChangeResult(7L, "AUDITOR", true, true);
        when(roleManagementService.changeRole(7L, "AUDITOR", true)).thenReturn(changed);

        var result = controller.changeRole(7L, "AUDITOR", request);

        assertEquals(200, result.getCode());
        assertEquals(changed, result.getData());
    }

    @Test
    void deniedRoleChangeCreatesASeparateDeniedAuditAttempt() {
        RoleAssignmentRequest request = new RoleAssignmentRequest();
        request.setEnabled(true);
        doThrow(new AccessDeniedException("forbidden"))
                .when(roleManagementService).changeRole(7L, "ORG_ADMIN", true);

        assertThrows(AccessDeniedException.class,
                () -> controller.changeRole(7L, "ORG_ADMIN", request));

        verify(auditService).appendDeniedSafely(
                eq("ROLE_CHANGE_DENIED"), eq("USER"), eq("7"),
                eq(Map.of("roleCode", "ORG_ADMIN")));
    }

    @Test
    void ownerTransferDoesNotAcceptMissingBody() {
        assertThrows(IllegalArgumentException.class, () -> controller.transferOwner(null));

        verify(auditService).appendDeniedSafely(
                "ORG_OWNER_TRANSFER_DENIED", "USER", null, Map.of());
    }
}
