package com.lanchat.recovery;

/** Strict, body-free fact. Authentication and business version transitions precede append. */
public record MutationFact(Type type, String conversationId, String messageId,
                           Long objectVersion, Long accessVersion, Boolean readAllowed,
                           Boolean sendAllowed, Boolean rebuildConversation, Reason reason) {
    public enum Type {
        MESSAGE_RECALLED, MESSAGE_BURNED, MESSAGE_UNAVAILABLE,
        CONVERSATION_ACCESS_REVOKED, CONVERSATION_ACCESS_CHANGED
    }
    public enum Reason { REMOVED, GROUP_REMOVED, DESTROYED, ACCESS_REVOKED,
        FRIEND_DELETED, SEND_DENIED, GRANTED, UPDATED }

    public MutationFact {
        if (type == null || !identifier(conversationId)) {
            throw new IllegalArgumentException("Invalid mutation identity");
        }
        boolean message = type == Type.MESSAGE_RECALLED || type == Type.MESSAGE_BURNED
                || type == Type.MESSAGE_UNAVAILABLE;
        if (message) {
            if (!identifier(messageId) || objectVersion == null || objectVersion <= 0
                    || accessVersion != null || readAllowed != null || sendAllowed != null
                    || rebuildConversation != null || reason != null) {
                throw new IllegalArgumentException("Invalid message mutation");
            }
        } else {
            if (messageId != null || objectVersion != null || accessVersion == null
                    || accessVersion <= 0 || readAllowed == null || sendAllowed == null || reason == null) {
                throw new IllegalArgumentException("Invalid access mutation");
            }
            boolean revokedReason = reason == Reason.REMOVED || reason == Reason.GROUP_REMOVED
                    || reason == Reason.DESTROYED || reason == Reason.ACCESS_REVOKED;
            if (type == Type.CONVERSATION_ACCESS_REVOKED) {
                if (readAllowed || sendAllowed || rebuildConversation != null || !revokedReason) {
                    throw new IllegalArgumentException("Invalid revoked access");
                }
            } else if (!readAllowed || rebuildConversation == null || revokedReason
                    || ((reason == Reason.FRIEND_DELETED || reason == Reason.SEND_DENIED)
                        && (sendAllowed || rebuildConversation))
                    || (reason == Reason.GRANTED && !rebuildConversation)) {
                throw new IllegalArgumentException("Invalid changed access");
            }
        }
    }

    private static boolean identifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 128;
    }

    public static MutationFact message(Type type, String conversationId, String messageId, long version) {
        return new MutationFact(type, conversationId, messageId, version, null, null, null, null, null);
    }
}
