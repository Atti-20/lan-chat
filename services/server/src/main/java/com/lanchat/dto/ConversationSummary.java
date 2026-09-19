package com.lanchat.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Authoritative per-user conversation list state.
 *
 * <p>The REST snapshot is the source of truth. Realtime events only advance
 * this state between snapshots.</p>
 */
@Data
public class ConversationSummary {

    private String conversationId;
    private String kind;
    private Long targetId;
    private Long lastSequence;
    private Long lastReadSequence;
    private Long unreadCount;
    private String lastMessage;
    private String lastMessageType;
    private LocalDateTime lastMessageAt;
    private Boolean pinned;
    private Boolean muted;
}
