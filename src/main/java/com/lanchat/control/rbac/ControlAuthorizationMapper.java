package com.lanchat.control.rbac;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ControlAuthorizationMapper {

    @Select("""
            SELECT DISTINCT permission.code
            FROM organization_member member
            JOIN organization ON organization.id = member.organization_id
            JOIN member_role ON member_role.member_id = member.id
            JOIN role ON role.id = member_role.role_id
            JOIN role_permission ON role_permission.role_id = role.id
            JOIN permission ON permission.id = role_permission.permission_id
            WHERE member.user_id = #{userId}
              AND organization.organization_key = #{organizationKey}
              AND organization.status = 'ACTIVE'
              AND member.status = 'ACTIVE'
              AND role.status = 'ACTIVE'
            ORDER BY permission.code
            """)
    List<String> selectPermissionCodes(@Param("userId") Long userId,
                                       @Param("organizationKey") String organizationKey);

    @Select("""
            SELECT COUNT(*)
            FROM organization_member member
            JOIN organization ON organization.id = member.organization_id
            JOIN member_role ON member_role.member_id = member.id
            JOIN role ON role.id = member_role.role_id
            WHERE member.user_id = #{userId}
              AND organization.organization_key = #{organizationKey}
              AND role.code = #{roleCode}
            """)
    int countRoleAssignment(@Param("userId") Long userId,
                            @Param("organizationKey") String organizationKey,
                            @Param("roleCode") String roleCode);

    @Select("""
            SELECT DISTINCT member.user_id
            FROM organization_member member
            JOIN organization ON organization.id = member.organization_id
            JOIN member_role ON member_role.member_id = member.id
            JOIN role ON role.id = member_role.role_id
            JOIN role_permission ON role_permission.role_id = role.id
            JOIN permission ON permission.id = role_permission.permission_id
            WHERE organization.organization_key = #{organizationKey}
              AND organization.status = 'ACTIVE'
              AND member.status = 'ACTIVE'
              AND role.status = 'ACTIVE'
              AND permission.code = #{permissionCode}
            ORDER BY member.user_id
            """)
    List<Long> selectUserIdsWithPermission(@Param("organizationKey") String organizationKey,
                                           @Param("permissionCode") String permissionCode);

    @Insert("""
            INSERT IGNORE INTO organization_member (organization_id, user_id, status, joined_at)
            SELECT organization.id, #{userId}, 'ACTIVE', CURRENT_TIMESTAMP
            FROM organization
            WHERE organization.organization_key = #{organizationKey}
              AND organization.status = 'ACTIVE'
            """)
    int ensureActiveMember(@Param("userId") Long userId,
                           @Param("organizationKey") String organizationKey);

    @Select("""
            SELECT member.id
            FROM organization_member member
            JOIN organization ON organization.id = member.organization_id
            WHERE member.user_id = #{userId}
              AND organization.organization_key = #{organizationKey}
            LIMIT 1
            """)
    Long selectMemberId(@Param("userId") Long userId,
                        @Param("organizationKey") String organizationKey);

    @Insert("""
            INSERT IGNORE INTO member_role (member_id, role_id, created_at)
            SELECT #{memberId}, role.id, CURRENT_TIMESTAMP
            FROM role
            JOIN organization ON organization.id = role.organization_id
            WHERE organization.organization_key = #{organizationKey}
              AND role.code = #{roleCode}
              AND role.status = 'ACTIVE'
            """)
    int ensureMemberRole(@Param("memberId") Long memberId,
                         @Param("organizationKey") String organizationKey,
                         @Param("roleCode") String roleCode);

    @Update("""
            UPDATE organization_member member
            JOIN organization ON organization.id = member.organization_id
            SET member.status = #{status},
                member.disabled_at = CASE WHEN #{status} = 'DISABLED' THEN CURRENT_TIMESTAMP ELSE NULL END
            WHERE member.user_id = #{userId}
              AND organization.organization_key = #{organizationKey}
            """)
    int updateMemberStatus(@Param("userId") Long userId,
                           @Param("organizationKey") String organizationKey,
                           @Param("status") String status);

    @Select("""
            SELECT role.id AS role_id,
                   role.code AS role_code,
                   role.name AS role_name,
                   permission.code AS permission_code,
                   permission.risk_level AS risk_level
            FROM role
            JOIN organization ON organization.id = role.organization_id
            LEFT JOIN role_permission ON role_permission.role_id = role.id
            LEFT JOIN permission ON permission.id = role_permission.permission_id
            WHERE organization.organization_key = #{organizationKey}
              AND role.status = 'ACTIVE'
            ORDER BY role.id, permission.code
            """)
    List<RolePermissionRow> selectRolePermissionRows(
            @Param("organizationKey") String organizationKey);

    @Select("""
            SELECT member.id AS member_id,
                   member.user_id AS user_id,
                   member.status
            FROM organization_member member
            JOIN organization ON organization.id = member.organization_id
            WHERE organization.organization_key = #{organizationKey}
              AND member.user_id = #{userId}
            LIMIT 1
            """)
    OrganizationMemberRow selectMember(@Param("organizationKey") String organizationKey,
                                       @Param("userId") Long userId);

    @Select("""
            SELECT role.code
            FROM organization_member member
            JOIN organization ON organization.id = member.organization_id
            JOIN member_role ON member_role.member_id = member.id
            JOIN role ON role.id = member_role.role_id
            WHERE organization.organization_key = #{organizationKey}
              AND member.user_id = #{userId}
              AND role.status = 'ACTIVE'
            ORDER BY role.code
            """)
    List<String> selectRoleCodes(@Param("organizationKey") String organizationKey,
                                 @Param("userId") Long userId);

    @Select("""
            SELECT role.id
            FROM role
            JOIN organization ON organization.id = role.organization_id
            WHERE organization.organization_key = #{organizationKey}
              AND role.code = #{roleCode}
              AND role.status = 'ACTIVE'
            LIMIT 1
            """)
    Long selectRoleId(@Param("organizationKey") String organizationKey,
                      @Param("roleCode") String roleCode);

    @Select("""
            SELECT COUNT(*)
            FROM role_permission
            JOIN permission ON permission.id = role_permission.permission_id
            WHERE role_permission.role_id = #{roleId}
              AND permission.risk_level = 'CRITICAL'
            """)
    int countCriticalPermissions(@Param("roleId") Long roleId);

    @Select("""
            SELECT COUNT(*)
            FROM member_role
            WHERE member_id = #{memberId}
              AND role_id = #{roleId}
            """)
    int countMemberRole(@Param("memberId") Long memberId,
                        @Param("roleId") Long roleId);

    @Insert("""
            INSERT INTO member_role (member_id, role_id, assigned_by, created_at)
            VALUES (#{memberId}, #{roleId}, #{assignedBy}, CURRENT_TIMESTAMP)
            """)
    int insertMemberRole(@Param("memberId") Long memberId,
                         @Param("roleId") Long roleId,
                         @Param("assignedBy") Long assignedBy);

    @Delete("""
            DELETE FROM member_role
            WHERE member_id = #{memberId}
              AND role_id = #{roleId}
            """)
    int deleteMemberRole(@Param("memberId") Long memberId,
                         @Param("roleId") Long roleId);

    @Select("""
            SELECT organization.id
            FROM organization
            WHERE organization.organization_key = #{organizationKey}
            FOR UPDATE
            """)
    Long lockOrganization(@Param("organizationKey") String organizationKey);

    @Select("""
            SELECT COUNT(*)
            FROM organization_member member
            JOIN organization ON organization.id = member.organization_id
            JOIN member_role ON member_role.member_id = member.id
            JOIN role ON role.id = member_role.role_id
            WHERE organization.organization_key = #{organizationKey}
              AND member.status = 'ACTIVE'
              AND role.code = 'ORG_OWNER'
            """)
    int countActiveOwners(@Param("organizationKey") String organizationKey);
}
