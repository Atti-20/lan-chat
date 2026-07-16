package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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

    /** ACTIVE / CANCELLED */
    private String status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
