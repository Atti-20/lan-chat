package com.lanchat.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * InnoDB journal primitive, not a capability-ready recovery service.
 * Caller holds conversation/access and message locks and has authenticated even retries.
 * One call supplies the entire frozen recipient set, after all business locks are acquired.
 */
@Service
public class MutationJournal {
    private final JdbcTemplate jdbc;

    public MutationJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Appended(long userId, String streamEpoch, String cursor, String eventId) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Appended> append(UUID sourceEventId, Collection<Long> recipients, MutationFact fact) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Mutation append requires the business write transaction");
        }
        if (sourceEventId == null || fact == null || recipients == null
                || recipients.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Invalid mutation recipients/source");
        }
        var results = new ArrayList<Appended>();
        // Identical global lock order across instances; duplicates never allocate a position.
        for (long user : new TreeSet<>(recipients)) {
            jdbc.update("""
                    INSERT INTO recovery_user_stream(user_id,stream_epoch) VALUES (?,?)
                    ON DUPLICATE KEY UPDATE user_id=user_id
                    """, user, UUID.randomUUID().toString());
            var stream = jdbc.queryForMap("""
                    SELECT stream_epoch,latest_cursor FROM recovery_user_stream WHERE user_id=? FOR UPDATE
                    """, user);
            String epoch = (String) stream.get("stream_epoch");
            String eventId = UUID.nameUUIDFromBytes((sourceEventId + ":" + user)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            var previous = jdbc.query("""
                    SELECT * FROM recovery_mutation WHERE user_id=? AND stream_epoch=? AND event_id=? FOR UPDATE
                    """, (rs, row) -> {
                MutationFact stored = new MutationFact(MutationFact.Type.valueOf(rs.getString("mutation_type")),
                        rs.getString("conversation_id"), rs.getString("message_id"),
                        rs.getObject("object_version", Long.class), rs.getObject("access_version", Long.class),
                        rs.getObject("read_allowed", Boolean.class), rs.getObject("send_allowed", Boolean.class),
                        rs.getObject("rebuild_conversation", Boolean.class),
                        rs.getString("reason") == null ? null : MutationFact.Reason.valueOf(rs.getString("reason")));
                if (!stored.equals(fact)) throw new IllegalStateException("Mutation idempotency conflict");
                return new Appended(user, epoch, Long.toString(rs.getLong("mutation_cursor")), eventId);
            }, user, epoch, eventId);
            if (!previous.isEmpty()) {
                results.add(previous.get(0));
                continue;
            }
            long latest = ((Number) stream.get("latest_cursor")).longValue();
            if (latest == Long.MAX_VALUE) throw new IllegalStateException("Mutation stream requires epoch rebuild");
            long cursor = latest + 1;
            LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO recovery_mutation(user_id,stream_epoch,mutation_cursor,event_id,mutation_type,
                    conversation_id,message_id,object_version,access_version,read_allowed,send_allowed,
                    rebuild_conversation,reason,committed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, user, epoch, cursor, eventId, fact.type().name(), fact.conversationId(), fact.messageId(),
                    fact.objectVersion(), fact.accessVersion(), fact.readAllowed(), fact.sendAllowed(),
                    fact.rebuildConversation(), fact.reason() == null ? null : fact.reason().name(), now);
            jdbc.update("""
                    INSERT INTO recovery_dispatch_outbox(user_id,stream_epoch,mutation_cursor,event_id,next_retry_at)
                    VALUES (?,?,?,?,?)
                    """, user, epoch, cursor, eventId, now);
            jdbc.update("UPDATE recovery_user_stream SET latest_cursor=? WHERE user_id=?", cursor, user);
            results.add(new Appended(user, epoch, Long.toString(cursor), eventId));
        }
        return List.copyOf(results);
    }
}
