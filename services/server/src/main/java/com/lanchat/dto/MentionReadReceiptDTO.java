package com.lanchat.dto;

import java.util.List;

/** Snapshot-based per-message read state for a group administrator's mention. */
public record MentionReadReceiptDTO(
        String messageId,
        int expectedCount,
        int readCount,
        int unreadCount,
        List<MentionReadRecipientDTO> recipients
) {
}
