package com.lanchat.control.rbac;

import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.security.UserContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AuthorizationService {

    private static final String OWNER_ROLE = "ORG_OWNER";
    private static final String MEMBER_ROLE = "MEMBER";

    private final ControlAuthorizationMapper authorizationMapper;
    private final ControlServerProperties controlServerProperties;

    public AuthorizationService(ControlAuthorizationMapper authorizationMapper,
                                ControlServerProperties controlServerProperties) {
        this.authorizationMapper = authorizationMapper;
        this.controlServerProperties = controlServerProperties;
    }

    public Set<PermissionCode> permissionCodes(Long userId) {
        if (userId == null || userId <= 0) return Set.of();
        List<String> codes = authorizationMapper.selectPermissionCodes(userId, organizationKey());
        if (codes == null || codes.isEmpty()) return Set.of();

        LinkedHashSet<PermissionCode> resolved = new LinkedHashSet<>();
        for (String code : codes) {
            try {
                resolved.add(PermissionCode.valueOf(code));
            } catch (IllegalArgumentException ignored) {
                // Unknown database permissions are ignored until this server version understands them.
            }
        }
        return Set.copyOf(resolved);
    }

    public boolean hasPermission(Long userId, PermissionCode permissionCode) {
        return permissionCode != null && permissionCodes(userId).contains(permissionCode);
    }

    public void requireCurrentUserPermission(PermissionCode permissionCode) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) throw new AccessDeniedException("请先登录");
        if (!hasPermission(userId, permissionCode)) {
            throw new AccessDeniedException("当前账号缺少权限：" + permissionCode.name());
        }
    }

    public boolean isOrganizationOwner(Long userId) {
        return userId != null
                && userId > 0
                && authorizationMapper.countRoleAssignment(userId, organizationKey(), OWNER_ROLE) > 0;
    }

    public List<Long> userIdsWithPermission(PermissionCode permissionCode) {
        if (permissionCode == null) return List.of();
        List<Long> userIds = authorizationMapper.selectUserIdsWithPermission(
                organizationKey(), permissionCode.name());
        return userIds == null ? List.of() : List.copyOf(userIds);
    }

    @Transactional
    public void provisionMember(Long userId) {
        provision(userId, false);
    }

    @Transactional
    public void provisionOwner(Long userId) {
        provision(userId, true);
    }

    public void synchronizeMemberStatus(Long userId, boolean active) {
        if (userId == null || userId <= 0) return;
        authorizationMapper.updateMemberStatus(
                userId, organizationKey(), active ? "ACTIVE" : "DISABLED");
    }

    private void provision(Long userId, boolean owner) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户标识无效");
        }
        authorizationMapper.ensureActiveMember(userId, organizationKey());
        Long memberId = authorizationMapper.selectMemberId(userId, organizationKey());
        if (memberId == null) {
            throw new IllegalStateException("组织未初始化，请先执行 V2.7 数据库迁移");
        }
        authorizationMapper.ensureMemberRole(memberId, organizationKey(), MEMBER_ROLE);
        if (owner) authorizationMapper.ensureMemberRole(memberId, organizationKey(), OWNER_ROLE);
    }

    private String organizationKey() {
        return controlServerProperties.resolvedOrganizationId();
    }
}
