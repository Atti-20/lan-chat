package com.lanchat.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Generated from contracts/websocket/envelope.schema.json. Do not edit. */
@Data
public class WebSocketEnvelope {

    private Integer version = 1;
    private String event;
    private String requestId;
    private String clientMsgId;
    private String conversationId;
    private Long timestamp;
    private Map<String, Object> payload = new LinkedHashMap<>();
}
