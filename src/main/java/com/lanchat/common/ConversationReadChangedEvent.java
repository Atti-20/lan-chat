package com.lanchat.common;

/**
 * A committed read-position update that must reach every device of the reader
 * and the other conversation participants.
 */
public record ConversationReadChangedEvent(
        String conversationId,
        Long userId,
        long lastSequence,
        long lastReadSequence,
        long unreadCount
) {
}
