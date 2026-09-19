package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persistent emergency broadcast definition. */
@Data
@TableName("broadcast")
public class Broadcast {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;
    private String title;
    private String content;

    private Boolean requireImageProof;
    private Boolean requireLocationProof;
    private LocalDateTime completedAt;

    /** ACTIVE / COMPLETED / CANCELLED */
    private String status;

    /** NORMAL / IMPORTANT / EMERGENCY */
    private String priority;

    /** ALL / GROUP / USERS */
    private String scopeType;
    private Long scopeGroupId;

    private Boolean confirmationRequired;

    /** JSON array of accepted confirmation values. */
    private String confirmationOptions;

    private LocalDateTime deadlineAt;
    private Boolean bypassMute;
    private Boolean repeatReminder;


    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** The requesting recipient's immutable submission state, populated only
     * by the visibility query. It keeps list filters user-specific without
     * changing the lifecycle state of the broadcast for its sender. */
    @TableField(exist = false)
    private String currentUserConfirmStatus;

    @TableField(exist = false)
    private LocalDateTime currentUserConfirmedAt;

    @TableField(exist = false)
    private LocalDateTime currentUserCompletedAt;
}
