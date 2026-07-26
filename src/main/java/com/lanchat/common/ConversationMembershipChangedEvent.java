package com.lanchat.common;

/** A committed membership removal that must evict stale client-side unread state. */
public record ConversationMembershipChangedEvent(
        String conversationId,
        Long userId,
        boolean active
) {
}
