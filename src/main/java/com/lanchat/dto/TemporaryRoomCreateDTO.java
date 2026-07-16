package com.lanchat.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TemporaryRoomCreateDTO {
    private String roomName;
    private String purpose;
    private LocalDateTime expiresAt;
    private Integer maxMembers;
    private Boolean allowGuests;
    private Boolean allowMemberInvite;
    private Boolean allowFileUpload;
    private Boolean allowFileDownload;
    private Boolean allowForward;
    private Integer messageRetentionDays;
    private Boolean allowExternalSync;
    private String expireAction;
}
