package com.lanchat.control.rbac;

import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {

    private ControlAuthorizationMapper mapper;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ControlAuthorizationMapper.class);
        ControlServerProperties properties = new ControlServerProperties();
        properties.setOrganizationId("org-acme");
        service = new AuthorizationService(mapper, properties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void permissionSnapshotUsesOrganizationMembershipAndIgnoresFutureCodes() {
        when(mapper.selectPermissionCodes(7L, "org-acme"))
                .thenReturn(List.of("USER_READ", "FUTURE_PERMISSION", "DEVICE_REVOKE"));

        var permissions = service.permissionCodes(7L);

        assertEquals(2, permissions.size());
        assertTrue(permissions.contains(PermissionCode.USER_READ));
        assertTrue(permissions.contains(PermissionCode.DEVICE_REVOKE));
    }

    @Test
    void currentUserPermissionFailsClosedWhenRoleDoesNotGrantIt() {
        authenticate(7L, "operations.lead");
        when(mapper.selectPermissionCodes(7L, "org-acme")).thenReturn(List.of("USER_READ"));

        assertThrows(AccessDeniedException.class,
                () -> service.requireCurrentUserPermission(PermissionCode.USER_DELETE));
    }

    @Test
    void ownerDetectionDependsOnRoleRatherThanUsername() {
        when(mapper.countRoleAssignment(7L, "org-acme", "ORG_OWNER")).thenReturn(1);

        assertTrue(service.isOrganizationOwner(7L));
        assertFalse(service.isOrganizationOwner(8L));
    }

    @Test
    void ownerProvisioningAlwaysIncludesBaseMembershipAndMemberRole() {
        when(mapper.selectMemberId(7L, "org-acme")).thenReturn(71L);

        service.provisionOwner(7L);

        verify(mapper).ensureActiveMember(7L, "org-acme");
        verify(mapper).ensureMemberRole(71L, "org-acme", "MEMBER");
        verify(mapper).ensureMemberRole(71L, "org-acme", "ORG_OWNER");
    }

    private void authenticate(Long userId, String username) {
        LoginUser loginUser = new LoginUser(userId, username, "web", "token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }
}
