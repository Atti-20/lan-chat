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
            SELECT b.*
            FROM broadcast b
            WHERE b.sender_id = #{userId}
               OR EXISTS (
                    SELECT 1
                    FROM broadcast_receiver br
                    WHERE br.broadcast_id = b.id AND br.user_id = #{userId}
               )
            ORDER BY b.create_time DESC, b.id DESC
            LIMIT 200
            """)
    List<Broadcast> selectVisible(@Param("userId") Long userId);

    /** Active, non-expired broadcasts that still need viewing or confirmation. */
    @Select("""
            SELECT b.*
            FROM broadcast b
            INNER JOIN broadcast_receiver br ON br.broadcast_id = b.id
            WHERE br.user_id = #{userId}
              AND b.status = 'ACTIVE'
              AND (b.deadline_at IS NULL OR b.deadline_at > NOW())
              AND (
                    br.viewed_at IS NULL
                    OR (b.confirmation_required = 1 AND br.confirm_status = 'PENDING')
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
