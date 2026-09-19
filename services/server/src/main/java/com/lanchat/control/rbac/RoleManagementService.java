package com.lanchat.control.rbac;

import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.security.UserContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RoleManagementService {

    private static final String ACTIVE = "ACTIVE";

    private final ControlAuthorizationMapper authorizationMapper;
    private final AuthorizationService authorizationService;
    private final ControlServerProperties controlServerProperties;
    private final ControlAuditService auditService;

    public RoleManagementService(ControlAuthorizationMapper authorizationMapper,
                                 AuthorizationService authorizationService,
                                 ControlServerProperties controlServerProperties,
                                 ControlAuditService auditService) {
        this.authorizationMapper = authorizationMapper;
        this.authorizationService = authorizationService;
        this.controlServerProperties = controlServerProperties;
        this.auditService = auditService;
    }

    public List<RoleView> listRoles() {
        authorizationService.requireCurrentUserPermission(PermissionCode.ROLE_ASSIGN);
        List<RolePermissionRow> rows = authorizationMapper.selectRolePermissionRows(organizationKey());
        if (rows == null || rows.isEmpty()) return List.of();

        Map<String, MutableRole> grouped = new LinkedHashMap<>();
        for (RolePermissionRow row : rows) {
            MutableRole role = grouped.computeIfAbsent(
                    row.getRoleCode(),
                    ignored -> new MutableRole(row.getRoleCode(), row.getRoleName()));
            if (row.getPermissionCode() != null) role.permissions.add(row.getPermissionCode());
            if ("CRITICAL".equals(row.getRiskLevel())) role.critical = true;
        }
        return grouped.values().stream().map(MutableRole::toView).toList();
    }

    public MemberRolesView memberRoles(Long userId) {
        authorizationService.requireCurrentUserPermission(PermissionCode.USER_READ);
        OrganizationMemberRow member = requireMember(userId);
        List<String> roles = authorizationMapper.selectRoleCodes(organizationKey(), userId);
        List<PermissionCode> permissions = new ArrayList<>(authorizationService.permissionCodes(userId));
        permissions.sort(Comparator.comparing(Enum::name));
        return new MemberRolesView(
                userId,
                member.getMemberId(),
                member.getStatus(),
                roles == null ? List.of() : List.copyOf(roles),
                List.copyOf(permissions));
    }

    @Transactional
    public RoleChangeResult changeRole(Long targetUserId, String rawRoleCode, boolean enabled) {
        Long actorUserId = requireActorWithRoleAssignment();
        if (actorUserId.equals(targetUserId)) {
            throw new AccessDeniedException("禁止修改自己的组织角色");
        }

        RoleCode roleCode = RoleCode.parse(rawRoleCode);
        if (roleCode == RoleCode.ORG_OWNER) {
            throw new IllegalArgumentException("组织所有者只能通过所有者转移接口变更");
        }
        if (roleCode == RoleCode.MEMBER) {
            throw new IllegalArgumentException("基础 MEMBER 角色不能移除或手工分配");
        }

        OrganizationMemberRow actor = requireActiveMember(actorUserId);
        OrganizationMemberRow target = requireActiveMember(targetUserId);
        Long roleId = requireRoleId(roleCode);
        if (authorizationMapper.countCriticalPermissions(roleId) > 0
                && !authorizationService.isOrganizationOwner(actorUserId)) {
            throw new AccessDeniedException("只有组织所有者可以管理含 CRITICAL 权限的角色");
        }

        boolean exists = authorizationMapper.countMemberRole(target.getMemberId(), roleId) > 0;
        boolean changed = false;
        if (enabled && !exists) {
            if (authorizationMapper.insertMemberRole(
                    target.getMemberId(), roleId, actor.getMemberId()) != 1) {
                throw new IllegalStateException("角色分配失败");
            }
            changed = true;
        } else if (!enabled && exists) {
            if (authorizationMapper.deleteMemberRole(target.getMemberId(), roleId) != 1) {
                throw new IllegalStateException("角色撤销失败");
            }
            changed = true;
        }

        auditService.appendRequired(
                actorUserId,
                enabled ? "ROLE_GRANTED" : "ROLE_REVOKED",
                "ORGANIZATION_MEMBER",
                String.valueOf(target.getMemberId()),
                "SUCCEEDED",
                Map.of(
                        "targetUserId", targetUserId,
                        "roleCode", roleCode.name(),
                        "changed", changed));
        return new RoleChangeResult(targetUserId, roleCode.name(), enabled, changed);
    }

    @Transactional
    public MemberRolesView transferOwner(Long targetUserId, String confirmationPhrase) {
        Long actorUserId = requireActorWithRoleAssignment();
        if (!authorizationService.isOrganizationOwner(actorUserId)) {
            throw new AccessDeniedException("只有当前组织所有者可以转移所有权");
        }
        if (actorUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("不能把组织所有权转移给自己");
        }
        String expectedPhrase = "TRANSFER OWNER TO " + targetUserId;
        if (!expectedPhrase.equals(confirmationPhrase)) {
            throw new IllegalArgumentException("确认短语不匹配，应输入：" + expectedPhrase);
        }

        OrganizationMemberRow actor = requireActiveMember(actorUserId);
        OrganizationMemberRow target = requireActiveMember(targetUserId);
        if (authorizationMapper.lockOrganization(organizationKey()) == null) {
            throw new IllegalStateException("组织不存在或未初始化");
        }
        Long ownerRoleId = requireRoleId(RoleCode.ORG_OWNER);
        if (authorizationMapper.countMemberRole(target.getMemberId(), ownerRoleId) > 0) {
            throw new IllegalArgumentException("目标成员已经是组织所有者");
        }
        if (authorizationMapper.countMemberRole(actor.getMemberId(), ownerRoleId) != 1) {
            throw new IllegalStateException("当前所有者角色状态异常");
        }

        if (authorizationMapper.insertMemberRole(
                target.getMemberId(), ownerRoleId, actor.getMemberId()) != 1) {
            throw new IllegalStateException("无法授予新组织所有者角色");
        }
        if (authorizationMapper.deleteMemberRole(actor.getMemberId(), ownerRoleId) != 1) {
            throw new IllegalStateException("无法撤销原组织所有者角色");
        }
        if (authorizationMapper.countActiveOwners(organizationKey()) != 1) {
            throw new IllegalStateException("所有者转移后组织所有者数量异常");
        }

        auditService.appendRequired(
                actorUserId,
                "ORG_OWNER_TRANSFERRED",
                "ORGANIZATION_MEMBER",
                String.valueOf(target.getMemberId()),
                "SUCCEEDED",
                Map.of(
                        "previousOwnerUserId", actorUserId,
                        "newOwnerUserId", targetUserId));
        return memberRolesWithoutPermissionCheck(targetUserId, target);
    }

    private Long requireActorWithRoleAssignment() {
        authorizationService.requireCurrentUserPermission(PermissionCode.ROLE_ASSIGN);
        Long actorUserId = UserContextHolder.getCurrentUserId();
        if (actorUserId == null) throw new AccessDeniedException("请先登录");
        return actorUserId;
    }

    private OrganizationMemberRow requireMember(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("用户标识无效");
        OrganizationMemberRow member = authorizationMapper.selectMember(organizationKey(), userId);
        if (member == null) throw new IllegalArgumentException("用户不属于当前组织");
        return member;
    }

    private OrganizationMemberRow requireActiveMember(Long userId) {
        OrganizationMemberRow member = requireMember(userId);
        if (!ACTIVE.equals(member.getStatus())) {
            throw new IllegalArgumentException("目标组织成员已停用");
        }
        return member;
    }

    private Long requireRoleId(RoleCode roleCode) {
        Long roleId = authorizationMapper.selectRoleId(organizationKey(), roleCode.name());
        if (roleId == null) throw new IllegalStateException("组织角色未初始化：" + roleCode.name());
        return roleId;
    }

    private MemberRolesView memberRolesWithoutPermissionCheck(Long userId,
                                                               OrganizationMemberRow member) {
        List<String> roles = authorizationMapper.selectRoleCodes(organizationKey(), userId);
        List<PermissionCode> permissions = new ArrayList<>(authorizationService.permissionCodes(userId));
        permissions.sort(Comparator.comparing(Enum::name));
        return new MemberRolesView(
                userId,
                member.getMemberId(),
                member.getStatus(),
                roles == null ? List.of() : List.copyOf(roles),
                List.copyOf(permissions));
    }

    private String organizationKey() {
        return controlServerProperties.resolvedOrganizationId();
    }

    private static final class MutableRole {
        private final String code;
        private final String name;
        private final List<String> permissions = new ArrayList<>();
        private boolean critical;

        private MutableRole(String code, String name) {
            this.code = code;
            this.name = name;
        }

        private RoleView toView() {
            return new RoleView(code, name, critical, List.copyOf(permissions));
        }
    }
}
