package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息唯一标识（UUID） */
    private String messageId;

    private Long fromUserId;

    /** 接收者用户ID（私聊） */
    private Long toUserId;

    /** 群组ID（群聊） */
    private Long groupId;

    /** 消息类型：text/image/file/voice/video */
    private String type;

    /** 附件对应的原始存储文件名，用于精确权限查询 */
    private String filePath;

    /** 消息内容 */
    private String content;

    /** 引用回复的消息ID */
    private String replyToId;

    /** @提及的用户ID列表（逗号分隔） */
    private String mentionUserIds;

    /** 是否阅后即焚：0-否 1-是 */
    private Integer isBurn;

    /** 焚毁倒计时（秒） */
    private Integer burnDuration;

    /** 是否已撤回：0-否 1-是 */
    private Integer isRecalled;

    /** 消息状态：0-未读 1-已读 2-已焚毁 */
    private Integer status;

    private LocalDateTime createTime;
}
