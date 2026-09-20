package com.lanchat.recovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MutationFactTest {
    @Test
    void messageFactsRejectMissingIdentityInvalidVersionsAndAccessFields() {
        for (var type : new MutationFact.Type[]{MutationFact.Type.MESSAGE_RECALLED,
                MutationFact.Type.MESSAGE_BURNED, MutationFact.Type.MESSAGE_UNAVAILABLE}) {
            assertNotNull(MutationFact.message(type, "g".repeat(128), "m".repeat(128), Long.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> MutationFact.message(type, "group:1", "", 2));
            assertThrows(IllegalArgumentException.class, () -> MutationFact.message(type, "group:1", "message", 0));
            assertThrows(IllegalArgumentException.class, () -> MutationFact.message(type, "g".repeat(129), "message", 2));
            assertThrows(IllegalArgumentException.class, () -> new MutationFact(type, "group:1", "message", 2L,
                    1L, true, true, false, null));
        }
    }

    @Test
    void accessFactsCannotGrantSendingWithoutReadingOrReviveDraftsOnGrant() {
        assertThrows(IllegalArgumentException.class, () -> changed(false, true, true, MutationFact.Reason.GRANTED));
        assertThrows(IllegalArgumentException.class, () -> changed(true, true, false, MutationFact.Reason.GRANTED));
        assertThrows(IllegalArgumentException.class, () -> changed(true, true, false, MutationFact.Reason.FRIEND_DELETED));
        assertThrows(IllegalArgumentException.class, () -> changed(true, false, true, MutationFact.Reason.SEND_DENIED));
        assertThrows(IllegalArgumentException.class, () -> changed(true, false, false, MutationFact.Reason.REMOVED));
        assertNotNull(changed(true, true, true, MutationFact.Reason.GRANTED));
        assertNotNull(changed(true, false, false, MutationFact.Reason.FRIEND_DELETED));
        assertNotNull(new MutationFact(MutationFact.Type.CONVERSATION_ACCESS_REVOKED, "group:1", null,
                null, 2L, false, false, null, MutationFact.Reason.REMOVED));
    }

    private MutationFact changed(boolean read, boolean send, boolean rebuild, MutationFact.Reason reason) {
        return new MutationFact(MutationFact.Type.CONVERSATION_ACCESS_CHANGED, "group:1", null,
                null, 2L, read, send, rebuild, reason);
    }
}
