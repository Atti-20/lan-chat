package com.lanchat.control.admin;

import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.device.DeviceManagementService;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.entity.User;
import com.lanchat.security.LoginUser;
import com.lanchat.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlAdminOperationServiceTest {

    private UserService userService;
    private ControlAuditService auditService;
    private DeviceManagementService deviceManagementService;
    private ControlAdminOperationService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        auditService = mock(ControlAuditService.class);
        deviceManagementService = mock(DeviceManagementService.class);
        service = new ControlAdminOperationService(
                userService, auditService, deviceManagementService);
        LoginUser actor = new LoginUser(1L, "owner.account", "web", "token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void statusMutationAndAuditShareTheApplicationServiceBoundary() {
        when(userService.setStatusByAdmin(7L, 0)).thenReturn(true);

        assertTrue(service.setStatus(7L, 0));

        verify(auditService).appendRequired(
                eq(1L), eq("USER_STATUS_CHANGED"), eq("USER"), eq("7"),
                eq("SUCCEEDED"), eq(Map.of("status", 0, "revokedDevices", 0)));
        verify(deviceManagementService).revokeAllForUser(7L, 1L, "ACCOUNT_DISABLED");
    }

    @Test
    void unsuccessfulMutationDoesNotClaimACompletedAudit() {
        when(userService.setBroadcastPermission(7L, true)).thenReturn(false);

        assertFalse(service.setBroadcastPermission(7L, true));

        verify(auditService, never()).appendRequired(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedBusinessMutationNeverWritesSuccessAudit() {
        when(userService.archiveUserByAdmin(7L, 1L))
                .thenThrow(new IllegalArgumentException("cannot archive"));

        assertThrows(IllegalArgumentException.class, () -> service.archiveUser(7L, 1L));

        verify(auditService, never()).appendRequired(any(), any(), any(), any(), any(), any());
    }

    @Test
    void accountCreationAuditsResolvedNumericTargetInsteadOfUsername() {
        RegisterDTO request = new RegisterDTO();
        request.setUsername("new.member");
        when(userService.register(request)).thenReturn(true);
        User created = new User();
        created.setId(17L);
        created.setUsername("new.member");
        when(userService.searchUsers("new.member")).thenReturn(List.of(created));

        assertTrue(service.createUser(request));

        verify(auditService).appendRequired(
                eq(1L), eq("USER_CREATED"), eq("USER"), eq("17"),
                eq("SUCCEEDED"), eq(Map.of()));
    }
}
