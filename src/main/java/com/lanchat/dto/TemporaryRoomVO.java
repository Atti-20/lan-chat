package com.lanchat.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TemporaryRoomVO {
    private Long id;
    private String conversationId;
    private String roomName;
    private String purpose;
    private Long ownerId;
    private String roomCode;
    private LocalDateTime expiresAt;
    private Integer maxMembers;
    private Long memberCount;
    private String currentUserRole;
    private Boolean allowGuests;
    private Boolean allowMemberInvite;
    private Boolean allowFileUpload;
    private Boolean allowFileDownload;
    private Boolean allowForward;
    private Integer messageRetentionDays;
    private Boolean allowExternalSync;
    private String expireAction;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
