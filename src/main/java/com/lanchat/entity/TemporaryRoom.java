package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("temporary_room")
public class TemporaryRoom {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roomName;
    private String purpose;
    private Long ownerId;
    private String roomCode;
    private LocalDateTime expiresAt;
    private Integer maxMembers;
    private Integer allowGuests;
    private Integer allowMemberInvite;
    private Integer allowFileUpload;
    private Integer allowFileDownload;
    private Integer allowForward;
    private Integer messageRetentionDays;
    private Integer allowExternalSync;

    /** 到期动作：FREEZE / ARCHIVE / DESTROY。 */
    private String expireAction;

    /** 生命周期状态：ACTIVE / FROZEN / ARCHIVED / DESTROYED。 */
    private String status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
