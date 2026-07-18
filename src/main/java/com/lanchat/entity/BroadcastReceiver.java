package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Per-recipient delivery, view and confirmation state. */
@Data
@TableName("broadcast_receiver")
public class BroadcastReceiver {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long broadcastId;
    private Long userId;
    private LocalDateTime deliveredAt;
    private LocalDateTime viewedAt;

    /** PENDING / NOT_REQUIRED / one of the broadcast confirmation options. */
    private String confirmStatus;

    private LocalDateTime confirmedAt;
    private String confirmDeviceType;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** ACTIVE / REMOVED */
    private String targetStatus;

    private LocalDateTime completedAt;
    private LocalDateTime removedAt;
    private Long removedBy;

    private Integer remindCount;
    private LocalDateTime lastRemindedAt;
}
