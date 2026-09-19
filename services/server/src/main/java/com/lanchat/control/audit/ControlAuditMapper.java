package com.lanchat.control.audit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ControlAuditMapper {

    @Insert("""
            INSERT INTO audit_event (
                organization_id,
                actor_member_id,
                actor_device_id,
                action,
                target_type,
                target_id,
                outcome,
                request_id,
                detail_json,
                created_at
            )
            SELECT organization.id,
                   member.id,
                   #{actorDeviceId},
                   #{action},
                   #{targetType},
                   #{targetId},
                   #{outcome},
                   #{requestId},
                   #{detailJson},
                   CURRENT_TIMESTAMP
            FROM organization
            LEFT JOIN organization_member member
              ON member.organization_id = organization.id
             AND member.user_id = #{actorUserId}
            WHERE organization.organization_key = #{organizationKey}
            """)
    int insertEvent(@Param("organizationKey") String organizationKey,
                    @Param("actorUserId") Long actorUserId,
                    @Param("actorDeviceId") Long actorDeviceId,
                    @Param("action") String action,
                    @Param("targetType") String targetType,
                    @Param("targetId") String targetId,
                    @Param("outcome") String outcome,
                    @Param("requestId") String requestId,
                    @Param("detailJson") String detailJson);

    @Select("""
            <script>
            SELECT audit.id,
                   actor.user_id AS actor_user_id,
                   audit.actor_device_id,
                   audit.action,
                   audit.target_type,
                   audit.target_id,
                   audit.outcome,
                   audit.request_id,
                   audit.detail_json,
                   audit.created_at
            FROM audit_event audit
            JOIN organization ON organization.id = audit.organization_id
            LEFT JOIN organization_member actor ON actor.id = audit.actor_member_id
            WHERE organization.organization_key = #{organizationKey}
            <if test="action != null">
              AND audit.action = #{action}
            </if>
            <if test="outcome != null">
              AND audit.outcome = #{outcome}
            </if>
            ORDER BY audit.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ControlAuditEventView> selectRecent(@Param("organizationKey") String organizationKey,
                                             @Param("action") String action,
                                             @Param("outcome") String outcome,
                                             @Param("limit") int limit);
}
