package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.Broadcast;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BroadcastMapper extends BaseMapper<Broadcast> {

    /**
     * Serializes broadcast cancellation and recipient confirmation on the same row.
     * Callers must execute inside a transaction.
     */
    @Select("""
            SELECT *
            FROM broadcast
            WHERE id = #{broadcastId}
            FOR UPDATE
            """)
    Broadcast selectByIdForUpdate(@Param("broadcastId") Long broadcastId);

    @Select("""
            SELECT b.*, br.confirm_status AS current_user_confirm_status,
                   br.confirmed_at AS current_user_confirmed_at,
                   br.completed_at AS current_user_completed_at
            FROM broadcast b
            LEFT JOIN broadcast_receiver br
                   ON br.broadcast_id = b.id
                  AND br.user_id = #{userId}
                  AND br.target_status = 'ACTIVE'
            WHERE b.sender_id = #{userId}
               OR (
                    b.status IN ('ACTIVE', 'COMPLETED')
                    AND br.id IS NOT NULL
               )
            ORDER BY b.create_time DESC, b.id DESC
            LIMIT 200
            """)
    List<Broadcast> selectVisible(@Param("userId") Long userId);

    /**
     * Active, non-expired broadcasts which the recipient has not finished.
     * A submitted confirmation is complete even if a legacy client never
     * persisted {@code viewed_at}; do not let that stale field revive it in
     * the actionable list.
     */
    @Select("""
            SELECT b.*
            FROM broadcast b
            INNER JOIN broadcast_receiver br ON br.broadcast_id = b.id
            WHERE br.user_id = #{userId}
              AND b.status = 'ACTIVE'
              AND br.target_status = 'ACTIVE'
              AND (b.deadline_at IS NULL OR b.deadline_at > NOW())
              AND (
                    (b.confirmation_required = 1
                     AND br.confirm_status = 'PENDING'
                     AND br.confirmed_at IS NULL)
                    OR (b.confirmation_required = 0 AND br.viewed_at IS NULL)
              )
            ORDER BY
                CASE b.priority
                    WHEN 'EMERGENCY' THEN 0
                    WHEN 'IMPORTANT' THEN 1
                    ELSE 2
                END,
                b.create_time DESC,
                b.id DESC
            LIMIT 200
            """)
    List<Broadcast> selectPending(@Param("userId") Long userId);

}
