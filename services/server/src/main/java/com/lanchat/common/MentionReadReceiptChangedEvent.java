package com.lanchat.common;

import java.util.List;

/**
 * A committed, immutable @-message receipt.  It carries no member read cursor
 * to clients: the websocket notifier only tells each original sender which
 * message rows must be refreshed through the guarded receipt endpoint.
 */
public record MentionReadReceiptChangedEvent(
        String conversationId,
        Long readerId,
        List<MessageChange> changes
) {
    public MentionReadReceiptChangedEvent {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public record MessageChange(String messageId, Long senderId) {
    }
}
