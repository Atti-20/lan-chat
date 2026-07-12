package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_group")
public class ChatGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String groupName;
    private String avatar;
    private String announcement;
    private Long ownerId;
    private Integer maxMembers;
    private Integer joinMode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
