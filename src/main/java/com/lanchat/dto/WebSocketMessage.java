package com.lanchat.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * WebSocket 传输的消息对象
 */
@Data
public class WebSocketMessage {

    /** 消息类型：chat / recall / burn / system / online-list / typing */
    private String type;

    /** 消息唯一标识（UUID） */
    private String messageId;

    /** 发送者用户ID */
    private Long fromUserId;

    /** 发送者昵称 */
    private String fromNickname;

    /** 发送者头像 */
    private String fromAvatar;

    /** 接收者用户ID（私聊） */
    private Long toUserId;

    /** 群组ID（群聊） */
    private Long groupId;

    /** 消息内容类型：text/image/file/voice/video */
    private String contentType;

    /** 消息内容 */
    private String content;

    /** 引用回复的消息ID */
    private String replyToId;

    /** @提及的用户ID列表（逗号分隔） */
    private String mentionUserIds;

    /** 是否阅后即焚 */
    private Boolean isBurn;

    /** 焚毁倒计时（秒） */
    private Integer burnDuration;

    /** 时间戳 */
    private LocalDateTime timestamp;
}
