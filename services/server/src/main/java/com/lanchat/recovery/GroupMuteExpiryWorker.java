package com.lanchat.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded retryable database reconciliation; no direct WS success notifications. */
@Component
@ConditionalOnProperty(name = "meshx.recovery.dual-write-enabled", havingValue = "true")
public class GroupMuteExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(GroupMuteExpiryWorker.class);
    private final JdbcTemplate jdbc;
    private final AccessMutationRecorder recorder;
    private final TransactionTemplate tx;

    public GroupMuteExpiryWorker(JdbcTemplate jdbc, AccessMutationRecorder recorder, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.recorder = recorder;
        this.tx = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${meshx.recovery.mute-expiry-delay-ms:1000}")
    public void reconcile() {
        var candidates = jdbc.query("""
                SELECT a.conversation_id,a.user_id FROM recovery_access_state a
                JOIN conversation c ON c.id=a.conversation_id AND c.status='ACTIVE'
                JOIN group_member m ON a.conversation_id=CONCAT('group:',m.group_id) AND a.user_id=m.user_id
                WHERE a.read_allowed=TRUE AND a.send_allowed=FALSE AND m.mute_until<?
                ORDER BY m.mute_until,a.conversation_id,a.user_id LIMIT 100
                """, (rs, row) -> new Candidate(rs.getString(1), rs.getLong(2)), java.time.LocalDateTime.now());
        for (var candidate : candidates) {
            try {
                tx.executeWithoutResult(status -> recorder.reconcileExpiredMute(candidate.cid(), candidate.user()));
            } catch (RuntimeException failure) {
                // Failed rows remain candidates; never advance a cursor or mark success.
                log.warn("Group mute expiry reconciliation failed; retained for retry", failure);
            }
        }
    }

    private record Candidate(String cid, long user) {}
}
