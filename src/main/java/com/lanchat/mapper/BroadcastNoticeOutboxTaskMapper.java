package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.BroadcastNoticeOutboxTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BroadcastNoticeOutboxTaskMapper extends BaseMapper<BroadcastNoticeOutboxTask> {

    /** Reusing an idempotency key must never reopen an already revoked task. */
    @Insert("""
            INSERT INTO broadcast_notice_outbox_task
                (idempotency_key, task_type, notice_kind, broadcast_id,
                 receiver_user_id, notice_generation, message_id, conversation_id,
                 status, attempts, lease_token, lease_until, next_retry_at,
                 last_error, create_time, update_time)
            VALUES
                (#{idempotencyKey}, #{taskType}, #{noticeKind}, #{broadcastId},
                 #{receiverUserId}, #{noticeGeneration}, #{messageId}, #{conversationId},
                 'PENDING', 0, NULL, NULL, NOW(), NULL, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                id = LAST_INSERT_ID(id),
                update_time = NOW()
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int enqueue(BroadcastNoticeOutboxTask task);

    @Select("SELECT * FROM broadcast_notice_outbox_task WHERE id = #{taskId} FOR UPDATE")
    BroadcastNoticeOutboxTask selectByIdForUpdate(@Param("taskId") Long taskId);

    @Select("""
            SELECT id
            FROM broadcast_notice_outbox_task
            WHERE (status = 'PENDING' AND next_retry_at <= NOW())
               OR (status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_until <= NOW())
            ORDER BY next_retry_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> selectDueTaskIds(@Param("limit") int limit);

    /** Source mutations lock broadcast first, then revoke pending routing work. */
    @Update("""
            UPDATE broadcast_notice_outbox_task
            SET status = 'REVOKED', lease_token = NULL, lease_until = NULL,
                last_error = #{reason}, update_time = NOW()
            WHERE broadcast_id = #{broadcastId}
              AND receiver_user_id = #{receiverUserId}
              AND notice_generation = #{noticeGeneration}
              AND task_type = 'SYNC'
              AND status IN ('PENDING', 'PROCESSING')
            """)
    int revokePendingRecipientDispatches(@Param("broadcastId") Long broadcastId,
                                         @Param("receiverUserId") Long receiverUserId,
                                         @Param("noticeGeneration") Integer noticeGeneration,
                                         @Param("reason") String reason);

    @Update("""
            UPDATE broadcast_notice_outbox_task
            SET status = 'REVOKED', lease_token = NULL, lease_until = NULL,
                last_error = #{reason}, update_time = NOW()
            WHERE broadcast_id = #{broadcastId}
              AND task_type = 'SYNC'
              AND status IN ('PENDING', 'PROCESSING')
            """)
    int revokePendingBroadcastDispatches(@Param("broadcastId") Long broadcastId,
                                         @Param("reason") String reason);

    @Update("""
            UPDATE broadcast_notice_outbox_task
            SET status = 'REVOKED', lease_token = NULL, lease_until = NULL,
                last_error = #{reason}, update_time = NOW()
            WHERE broadcast_id = #{broadcastId}
              AND receiver_user_id = #{receiverUserId}
              AND notice_generation = #{noticeGeneration}
              AND task_type = 'SYNC'
              AND notice_kind = 'REMINDER'
              AND status IN ('PENDING', 'PROCESSING')
            """)
    int revokePendingReminderDispatches(@Param("broadcastId") Long broadcastId,
                                        @Param("receiverUserId") Long receiverUserId,
                                        @Param("noticeGeneration") Integer noticeGeneration,
                                        @Param("reason") String reason);
}
