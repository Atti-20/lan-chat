package com.lanchat.control.audit;

import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.control.rbac.PermissionCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlAuditControllerTest {

    @Test
    void auditQueryAlwaysChecksServerSideAuditPermission() {
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        ControlAuditService auditService = mock(ControlAuditService.class);
        ControlAuditEventView event = new ControlAuditEventView();
        event.setId(9L);
        when(auditService.recent("ROLE_GRANTED", "SUCCEEDED", 50))
                .thenReturn(List.of(event));
        ControlAuditController controller = new ControlAuditController(
                authorizationService, auditService);

        var result = controller.recent(50, "ROLE_GRANTED", "SUCCEEDED");

        assertEquals(List.of(event), result.getData());
        verify(authorizationService).requireCurrentUserPermission(PermissionCode.AUDIT_READ);
    }
}
