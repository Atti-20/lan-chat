package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @JsonIgnore
    private String password;

    private String nickname;
    private String avatar;
    private String signature;
    private Integer online;
    private LocalDateTime lastLoginAt;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 全局免打扰开始时段（如 "22:00"） */
    private String muteStart;

    /** 全局免打扰结束时段（如 "08:00"） */
    private String muteEnd;

    /** 非数据库字段：设备类型 */
    @TableField(exist = false)
    private String deviceType;
}
