package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable, post-commit delivery intent for a technical broadcast notice.
 *
 * <p>The task deliberately stores only routing metadata.  Broadcast card
 * content is committed with the business change and is fetched from the
 * normal conversation sync path after this task emits a lightweight refresh.
 * That keeps a stale or retried realtime frame from carrying revoked content.</p>
 */
@Data
@TableName("broadcast_notice_outbox_task")
public class BroadcastNoticeOutboxTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String idempotencyKey;
    /** SYNC / RECALL */
    private String taskType;
    /** OVERVIEW / REMINDER for SYNC tasks. */
    private String noticeKind;
    private Long broadcastId;
    private Long receiverUserId;
    private Integer noticeGeneration;
    private String messageId;
    private String conversationId;
    /** PENDING / PROCESSING / DISPATCHED / REVOKED */
    private String status;
    private Integer attempts;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
