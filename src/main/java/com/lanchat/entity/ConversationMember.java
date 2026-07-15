package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation_member")
public class ConversationMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;
    private Long userId;
    private String role;
    private Long lastReadSequence;
    private Integer unreadCount;
    private Integer isMuted;
    private Integer isPinned;
    private LocalDateTime joinTime;
    private LocalDateTime leftTime;
}
