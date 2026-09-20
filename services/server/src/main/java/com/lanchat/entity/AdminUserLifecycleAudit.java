package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable, non-PII audit record for administrator account lifecycle operations.
 */
@Data
@TableName("admin_user_lifecycle_audit")
public class AdminUserLifecycleAudit {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long actorUserId;
    private Long targetUserId;
    /** ARCHIVED / PHYSICALLY_ERASED. */
    private String action;
    private String reason;
    /** Bounded machine-readable counts only; never usernames, tokens or message content. */
    private String detail;
    private LocalDateTime createTime;
}
