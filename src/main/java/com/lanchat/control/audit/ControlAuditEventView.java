package com.lanchat.control.audit;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ControlAuditEventView {
    private Long id;
    private Long actorUserId;
    private Long actorDeviceId;
    private String action;
    private String targetType;
    private String targetId;
    private String outcome;
    private String requestId;
    private String detailJson;
    private LocalDateTime createdAt;
}
