package com.lanchat.recovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Conversation lock precedes access metadata and per-user stream locks. */
@Service
public class AccessMutationRecorder {
    private final JdbcTemplate jdbc;
    private final MutationJournal journal;
    private final ConversationWriteGuard guard;
    private final boolean enabled;

    public AccessMutationRecorder(JdbcTemplate jdbc, MutationJournal journal, ConversationWriteGuard guard,
                                  @Value("${meshx.recovery.dual-write-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.guard = guard;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    public boolean hasDirectoryEntry(String cid, Long user) {
        guard.lock(cid);
        return !jdbc.queryForList("SELECT user_id FROM conversation_member WHERE conversation_id=? AND user_id=? AND left_time IS NULL FOR UPDATE",
                Long.class,cid,user).isEmpty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void directoryAdded(String cid, Long user) {
        if (!enabled) return;
        var current=guard.permission(cid,user);
        if (!current.readAllowed() || !hasDirectoryEntry(cid,user)) throw new IllegalStateException("Directory grant requires a current readable membership");
        record(cid,user,current,current,MutationFact.Reason.GRANTED,true,true);
    }

    /** Capture under the same business transaction before changing membership. */
    public ConversationWriteGuard.Permission before(String cid, Long user) {
        return enabled ? guard.permission(cid, user) : null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(String cid, Long user, ConversationWriteGuard.Permission before, MutationFact.Reason reason) {
        if (!enabled) return;
        var after = guard.permission(cid, user);
        if (before == null || !before.readAllowed() || after.readAllowed() || after.sendAllowed()) {
            throw new IllegalStateException("Access revocation requires a locked readable-to-denied transition");
        }
        record(cid, user, before, after, reason, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void grant(String cid, Long user, ConversationWriteGuard.Permission before) {
        if (!enabled) return;
        var after = guard.permission(cid, user);
        if (before == null || before.readAllowed() || !after.readAllowed()) {
            throw new IllegalStateException("Access grant requires a locked denied-to-readable transition");
        }
        record(cid, user, before, after, MutationFact.Reason.GRANTED, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshReadable(String cid, Long user, ConversationWriteGuard.Permission before,
                                MutationFact.Reason deniedReason, boolean rebuildOnEnable) {
        if (!enabled) return;
        var after = guard.permission(cid, user);
        if (before == null || !before.readAllowed() || !after.readAllowed()) {
            throw new IllegalStateException("Readable access update cannot revoke history");
        }
        if (sameAccess(before, after) && before.expiredMuteDeadline() == null && before.expiredRoomDeadline() == null) return;
        boolean rebuild = after.sendAllowed() && rebuildOnEnable;
        var reason = after.sendAllowed() ? (rebuild ? MutationFact.Reason.GRANTED : MutationFact.Reason.UPDATED)
                : deniedReason;
        record(cid, user, before, after, reason, rebuild);
    }

    /** Scheduled candidates are advisory: recheck the deadline under the conversation lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileExpiredMute(String cid, Long user) {
        if (!enabled) return;
        var current = guard.permission(cid, user);
        if (current.expiredMuteDeadline() == null || !current.sendAllowed()) return;
        record(cid, user, current, current, MutationFact.Reason.UPDATED, false);
    }

    private boolean sameAccess(ConversationWriteGuard.Permission first, ConversationWriteGuard.Permission second) {
        return first.readAllowed() == second.readAllowed() && first.sendAllowed() == second.sendAllowed();
    }

    private void record(String cid, Long user, ConversationWriteGuard.Permission before,
                        ConversationWriteGuard.Permission after, MutationFact.Reason reason, Boolean rebuild) {
        record(cid,user,before,after,reason,rebuild,false);
    }

    private void record(String cid, Long user, ConversationWriteGuard.Permission before,
                        ConversationWriteGuard.Permission after, MutationFact.Reason reason, Boolean rebuild, boolean forceDirectory) {
        jdbc.update("""
                INSERT INTO recovery_access_state(conversation_id,user_id,access_version,read_allowed,send_allowed)
                VALUES (?,?,1,?,?) ON DUPLICATE KEY UPDATE user_id=user_id
                """, cid, user, before.readAllowed(), before.sendAllowed());
        var state = jdbc.queryForObject("""
                SELECT access_version,read_allowed,send_allowed FROM recovery_access_state
                WHERE conversation_id=? AND user_id=? FOR UPDATE
                """, (rs, n) -> new State(rs.getLong(1), rs.getBoolean(2), rs.getBoolean(3)), cid, user);
        // Capture contains proof from the locked pre-mutation membership. Emit expiration
        // here, after business row work, rather than taking a user stream lock in before().
        if (state != null && state.read() && !state.send() && before.readAllowed() && before.sendAllowed()
                && before.expiredMuteDeadline() != null) {
            long expiredVersion = Math.addExact(state.version(), 1);
            append(cid, user, expiredVersion, before, MutationFact.Reason.UPDATED, false);
            state = new State(expiredVersion, true, true);
        }
        if (state != null && state.read() && state.send() && before.readAllowed() && !before.sendAllowed()
                && before.expiredRoomDeadline() != null) {
            long expiredVersion = Math.addExact(state.version(), 1);
            append(cid, user, expiredVersion, before, MutationFact.Reason.SEND_DENIED, false);
            state = new State(expiredVersion, true, false);
        }
        if (state == null || state.read() != before.readAllowed() || state.send() != before.sendAllowed()) {
            throw new IllegalStateException("Access metadata requires reconciliation before dual write");
        }
        if (sameAccess(before, after) && !forceDirectory) return;
        long version = Math.addExact(state.version(), 1);
        append(cid, user, version, after, reason, rebuild);
    }

    private void append(String cid, Long user, long version, ConversationWriteGuard.Permission after,
                        MutationFact.Reason reason, Boolean rebuild) {
        var fact = new MutationFact(after.readAllowed() ? MutationFact.Type.CONVERSATION_ACCESS_CHANGED
                : MutationFact.Type.CONVERSATION_ACCESS_REVOKED, cid, null, null,
                version, after.readAllowed(), after.sendAllowed(), rebuild, reason);
        jdbc.update("""
                UPDATE recovery_access_state SET access_version=?,read_allowed=?,send_allowed=?
                WHERE conversation_id=? AND user_id=?
                """, version, after.readAllowed(), after.sendAllowed(), cid, user);
        var event = UUID.nameUUIDFromBytes(("access:" + cid + ":" + user + ":" + version)
                .getBytes(StandardCharsets.UTF_8));
        journal.append(event, List.of(user), fact);
    }

    private record State(long version, boolean read, boolean send) {}
}
