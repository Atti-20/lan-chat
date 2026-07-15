package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.INPUT)
    private String id;

    /** PRIVATE / GROUP / TEMPORARY / SYSTEM / BROADCAST */
    private String type;

    /** GROUP 会话对应的旧群组 ID；私聊为空。 */
    private Long sourceId;

    private String lastMessageId;
    private Long lastSequence;

    /** ACTIVE / READ_ONLY / ARCHIVED / DESTROYED */
    private String status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
