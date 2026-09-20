package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.BroadcastReceiver;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BroadcastReceiverMapper extends BaseMapper<BroadcastReceiver> {

    @Select("""
            SELECT *
            FROM broadcast_receiver
            WHERE broadcast_id = #{broadcastId} AND user_id = #{userId}
              AND target_status = 'ACTIVE'
            """)
    BroadcastReceiver selectReceiver(@Param("broadcastId") Long broadcastId,
                                     @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM broadcast_receiver
            WHERE broadcast_id = #{broadcastId} AND user_id = #{userId}
            """)
    BroadcastReceiver selectReceiverIncludingRemoved(@Param("broadcastId") Long broadcastId,
                                                     @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM broadcast_receiver
            WHERE broadcast_id = #{broadcastId}
            ORDER BY id
            """)
    List<BroadcastReceiver> selectByBroadcastId(@Param("broadcastId") Long broadcastId);

    @Update("""
            UPDATE broadcast_receiver
            SET delivered_at = COALESCE(delivered_at, NOW()),
                update_time = NOW()
            WHERE broadcast_id = #{broadcastId} AND user_id = #{userId}
              AND target_status = 'ACTIVE'
            """)
    int markDelivered(@Param("broadcastId") Long broadcastId,
                      @Param("userId") Long userId);

    @Update("""
            UPDATE broadcast_receiver
            SET delivered_at = COALESCE(delivered_at, NOW()),
                viewed_at = COALESCE(viewed_at, NOW()),
                update_time = NOW()
            WHERE broadcast_id = #{broadcastId} AND user_id = #{userId}
              AND target_status = 'ACTIVE'
            """)
    int markViewed(@Param("broadcastId") Long broadcastId,
                   @Param("userId") Long userId);

    /** Only the first confirmation wins; callers re-read to make retries idempotent. */
    @Update("""
            UPDATE broadcast_receiver
            SET delivered_at = COALESCE(delivered_at, NOW()),
                viewed_at = COALESCE(viewed_at, NOW()),
                confirm_status = #{status},
                confirmed_at = NOW(),
                confirm_device_type = #{deviceType},
                update_time = NOW()
            WHERE broadcast_id = #{broadcastId}
              AND user_id = #{userId}
              AND (confirm_status = 'PENDING'
                   OR (confirm_status = 'NEED_SUPPORT' AND #{status} = 'EXECUTED'))
            """)
    int confirmIfPending(@Param("broadcastId") Long broadcastId,
                         @Param("userId") Long userId,
                         @Param("status") String status,
                         @Param("deviceType") String deviceType);
}
