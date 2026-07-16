package com.lanchat.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** Request for creating a persisted emergency broadcast. */
@Data
public class BroadcastCreateDTO {
    private String title;
    private String content;
    private String priority;
    private String scopeType;
    private Long groupId;
    private List<Long> receiverIds;
    private Boolean confirmationRequired;
    private List<String> confirmationOptions;
    private LocalDateTime deadlineAt;
    private Boolean bypassMute;
    private Boolean repeatReminder;
}
