package com.lanchat.common;

/** A committed membership or role change that must invalidate stale client-side state. */
public record ConversationMembershipChangedEvent(
        String conversationId,
        Long userId,
        boolean active
) {
}
