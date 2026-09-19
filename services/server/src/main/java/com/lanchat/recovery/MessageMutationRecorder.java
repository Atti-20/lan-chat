package com.lanchat.recovery;

import com.lanchat.entity.ChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;

/** Additive dual-write stage only; enabling this never advertises recovery capability. */
@Service
public class MessageMutationRecorder {
    private final JdbcTemplate jdbc;
    private final MutationJournal journal;
    private final boolean enabled;

    public MessageMutationRecorder(JdbcTemplate jdbc, MutationJournal journal,
            @Value("${meshx.recovery.dual-write-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void created(ChatMessage message) {
        if (!enabled) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || message == null || message.getSequence() == null || message.getSequence() <= 0
                || Integer.valueOf(1).equals(message.getIsRecalled()) || Integer.valueOf(2).equals(message.getStatus())) {
            throw new IllegalStateException("New recovery message requires a normal row in its write transaction");
        }
        // A surviving tombstone forbids reusing its identity, including after physical deletion.
        jdbc.update("INSERT INTO recovery_message_state(message_id,conversation_id,message_sequence,object_version,state) VALUES (?,?,?,1,'NORMAL')",
                message.getMessageId(),message.getConversationId(),message.getSequence());
    }

    /** previous is a locked business row before the transition, never an untrusted DTO. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ChatMessage previous, MutationFact.Type type, Collection<Long> recipients) {
        if (!enabled) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Message state requires the business write transaction");
        }
        if (previous == null || previous.getSequence() == null || previous.getSequence() <= 0) {
            throw new IllegalStateException("Message state requires a reliable sequence");
        }
        String target = switch (type) {
            case MESSAGE_RECALLED -> "RECALLED";
            case MESSAGE_BURNED -> "BURNED";
            case MESSAGE_UNAVAILABLE -> "UNAVAILABLE";
            default -> throw new IllegalArgumentException("Not a message mutation");
        };
        String before = Integer.valueOf(1).equals(previous.getIsRecalled()) ? "RECALLED"
                : Integer.valueOf(2).equals(previous.getStatus()) ? "BURNED" : "NORMAL";
        // New metadata may start only from a proven current NORMAL row. Legacy terminal
        // rows require the separately reviewed backfill; never invent historical versions.
        if ("NORMAL".equals(before)) {
            jdbc.update("""
                    INSERT INTO recovery_message_state(message_id,conversation_id,message_sequence,object_version,state)
                    VALUES (?,?,?,1,'NORMAL') ON DUPLICATE KEY UPDATE message_id=message_id
                    """, previous.getMessageId(), previous.getConversationId(), previous.getSequence());
        }
        var states = jdbc.queryForList("SELECT * FROM recovery_message_state WHERE message_id=? FOR UPDATE",
                previous.getMessageId());
        if (states.size() != 1) throw new IllegalStateException("Message state requires backfill");
        var state = states.get(0);
        if (!previous.getConversationId().equals(state.get("conversation_id"))
                || previous.getSequence() != ((Number) state.get("message_sequence")).longValue()
                || !before.equals(state.get("state"))) {
            throw new IllegalStateException("Message state diverged from locked business row");
        }
        if (before.equals(target)) return;
        if (!"NORMAL".equals(before) && !"UNAVAILABLE".equals(target)) {
            throw new IllegalStateException("Terminal message state cannot be replaced");
        }
        long version = ((Number) state.get("object_version")).longValue();
        if (version == Long.MAX_VALUE) throw new IllegalStateException("Message version exhausted");
        version++;
        MutationFact fact = MutationFact.message(type, previous.getConversationId(), previous.getMessageId(), version);
        UUID source = UUID.nameUUIDFromBytes(("message:" + previous.getMessageId() + ":" + version)
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE recovery_message_state SET object_version=?,state=? WHERE message_id=?",
                version, target, previous.getMessageId());
        journal.append(source, recipients, fact);
    }
}
