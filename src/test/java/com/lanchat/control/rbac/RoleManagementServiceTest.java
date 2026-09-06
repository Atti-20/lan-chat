package com.lanchat.control.rbac;

import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleManagementServiceTest {

    private ControlAuthorizationMapper mapper;
    private AuthorizationService authorizationService;
    private ControlAuditService auditService;
    private RoleManagementService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ControlAuthorizationMapper.class);
        authorizationService = mock(AuthorizationService.class);
        auditService = mock(ControlAuditService.class);
        ControlServerProperties properties = new ControlServerProperties();
        properties.setOrganizationId("org-acme");
        service = new RoleManagementService(
                mapper, authorizationService, properties, auditService);
        authenticate(1L, "owner.account");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void actorCanNeverChangeOwnRole() {
        assertThrows(AccessDeniedException.class,
                () -> service.changeRole(1L, "AUDITOR", true));

        verify(mapper, never()).insertMemberRole(any(), any(), any());
        verify(auditService, never()).appendRequired(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonOwnerCannotManageRoleContainingCriticalPermission() {
        member(1L, 11L);
        member(7L, 71L);
        when(mapper.selectRoleId("org-acme", "ORG_ADMIN")).thenReturn(21L);
        when(mapper.countCriticalPermissions(21L)).thenReturn(1);
        when(authorizationService.isOrganizationOwner(1L)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> service.changeRole(7L, "ORG_ADMIN", true));

        verify(mapper, never()).insertMemberRole(any(), any(), any());
    }

    @Test
    void ownerCanGrantCriticalRoleAndAuditTheChange() {
        member(1L, 11L);
        member(7L, 71L);
        when(mapper.selectRoleId("org-acme", "SECURITY_ADMIN")).thenReturn(22L);
        when(mapper.countCriticalPermissions(22L)).thenReturn(1);
        when(authorizationService.isOrganizationOwner(1L)).thenReturn(true);
        when(mapper.countMemberRole(71L, 22L)).thenReturn(0);
        when(mapper.insertMemberRole(71L, 22L, 11L)).thenReturn(1);

        RoleChangeResult result = service.changeRole(7L, "security_admin", true);

        assertTrue(result.changed());
        assertEquals("SECURITY_ADMIN", result.roleCode());
        verify(auditService).appendRequired(
                eq(1L), eq("ROLE_GRANTED"), eq("ORGANIZATION_MEMBER"), eq("71"),
                eq("SUCCEEDED"), any(Map.class));
    }

    @Test
    void repeatedGrantIsIdempotentButStillAudited() {
        member(1L, 11L);
        member(7L, 71L);
        when(mapper.selectRoleId("org-acme", "AUDITOR")).thenReturn(26L);
        when(mapper.countMemberRole(71L, 26L)).thenReturn(1);

        RoleChangeResult result = service.changeRole(7L, "AUDITOR", true);

        assertFalse(result.changed());
        verify(mapper, never()).insertMemberRole(any(), any(), any());
        verify(auditService).appendRequired(
                eq(1L), eq("ROLE_GRANTED"), eq("ORGANIZATION_MEMBER"), eq("71"),
                eq("SUCCEEDED"), any(Map.class));
    }

    @Test
    void ownerTransferIsSerializedAtomicAndLeavesExactlyOneOwner() {
        member(1L, 11L);
        member(7L, 71L);
        when(authorizationService.isOrganizationOwner(1L)).thenReturn(true);
        when(mapper.lockOrganization("org-acme")).thenReturn(3L);
        when(mapper.selectRoleId("org-acme", "ORG_OWNER")).thenReturn(20L);
        when(mapper.countMemberRole(71L, 20L)).thenReturn(0);
        when(mapper.countMemberRole(11L, 20L)).thenReturn(1);
        when(mapper.insertMemberRole(71L, 20L, 11L)).thenReturn(1);
        when(mapper.deleteMemberRole(11L, 20L)).thenReturn(1);
        when(mapper.countActiveOwners("org-acme")).thenReturn(1);
        when(mapper.selectRoleCodes("org-acme", 7L)).thenReturn(List.of("MEMBER", "ORG_OWNER"));
        when(authorizationService.permissionCodes(7L)).thenReturn(java.util.Set.of(PermissionCode.ROLE_ASSIGN));

        MemberRolesView result = service.transferOwner(7L, "TRANSFER OWNER TO 7");

        assertEquals(List.of("MEMBER", "ORG_OWNER"), result.roles());
        InOrder order = inOrder(mapper, auditService);
        order.verify(mapper).lockOrganization("org-acme");
        order.verify(mapper).insertMemberRole(71L, 20L, 11L);
        order.verify(mapper).deleteMemberRole(11L, 20L);
        order.verify(mapper).countActiveOwners("org-acme");
        order.verify(auditService).appendRequired(
                eq(1L), eq("ORG_OWNER_TRANSFERRED"), eq("ORGANIZATION_MEMBER"),
                eq("71"), eq("SUCCEEDED"), any(Map.class));
    }

    @Test
    void ownerTransferRequiresExactConfirmationPhraseBeforeLocking() {
        when(authorizationService.isOrganizationOwner(1L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.transferOwner(7L, "TRANSFER 7"));

        verify(mapper, never()).lockOrganization(any());
    }

    @Test
    void roleCatalogMarksRolesWithCriticalPermission() {
        RolePermissionRow admin = roleRow(20L, "ORG_ADMIN", "组织管理员", "ROLE_ASSIGN", "CRITICAL");
        RolePermissionRow auditor = roleRow(21L, "AUDITOR", "审计员", "AUDIT_READ", "HIGH");
        when(mapper.selectRolePermissionRows("org-acme")).thenReturn(List.of(admin, auditor));

        List<RoleView> roles = service.listRoles();

        assertEquals(2, roles.size());
        assertTrue(roles.get(0).critical());
        assertFalse(roles.get(1).critical());
    }

    private void member(Long userId, Long memberId) {
        OrganizationMemberRow member = new OrganizationMemberRow();
        member.setUserId(userId);
        member.setMemberId(memberId);
        member.setStatus("ACTIVE");
        when(mapper.selectMember("org-acme", userId)).thenReturn(member);
    }

    private RolePermissionRow roleRow(Long roleId,
                                      String roleCode,
                                      String roleName,
                                      String permissionCode,
                                      String riskLevel) {
        RolePermissionRow row = new RolePermissionRow();
        row.setRoleId(roleId);
        row.setRoleCode(roleCode);
        row.setRoleName(roleName);
        row.setPermissionCode(permissionCode);
        row.setRiskLevel(riskLevel);
        return row;
    }

    private void authenticate(Long userId, String username) {
        LoginUser loginUser = new LoginUser(userId, username, "web", "token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }
}
