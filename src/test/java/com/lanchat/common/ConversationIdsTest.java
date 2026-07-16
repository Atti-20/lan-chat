package com.lanchat.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationIdsTest {

    @Test
    void privateConversationIdIsDeterministic() {
        assertEquals("private:7:42", ConversationIds.privateConversation(42L, 7L));
        assertEquals("private:7:42", ConversationIds.privateConversation(7L, 42L));

        var participants = ConversationIds.parsePrivate("private:7:42").orElseThrow();
        assertTrue(participants.contains(7L));
        assertEquals(42L, participants.peerOf(7L));
    }

    @Test
    void rejectsInvalidConversationIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> ConversationIds.privateConversation(7L, 7L));
        assertTrue(ConversationIds.parsePrivate("private:42:7").isEmpty());
        assertTrue(ConversationIds.parseGroup("group:not-a-number").isEmpty());
        assertTrue(ConversationIds.parseTemporary("temporary:0").isEmpty());
    }

    @Test
    void temporaryConversationIdIsStrictlyParsed() {
        assertEquals("temporary:19", ConversationIds.temporaryConversation(19L));
        assertEquals(19L, ConversationIds.parseTemporary("temporary:19").orElseThrow());
        assertTrue(ConversationIds.parseTemporary("group:19").isEmpty());
    }
}
