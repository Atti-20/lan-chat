package com.lanchat.recovery;

import com.lanchat.common.ConversationIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/** Common conversation-first lock and current reads; never authorize writes from an old MVCC view. */
@Service
public class ConversationWriteGuard {
    private final JdbcTemplate jdbc;
    public ConversationWriteGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Permission(boolean readAllowed, boolean sendAllowed, LocalDateTime expiredMuteDeadline,
                             LocalDateTime expiredRoomDeadline) {
        public Permission(boolean readAllowed, boolean sendAllowed) { this(readAllowed, sendAllowed, null, null); }
        public Permission(boolean readAllowed, boolean sendAllowed, LocalDateTime expiredMuteDeadline) {
            this(readAllowed,sendAllowed,expiredMuteDeadline,null);
        }
    }
    private static final Permission DENIED = new Permission(false, false);

    public String lock(String conversationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Conversation writes require a write transaction");
        }
        var statuses = jdbc.queryForList("SELECT status FROM conversation WHERE id=? FOR UPDATE", String.class, conversationId);
        if (statuses.size() != 1) throw new IllegalArgumentException("会话不存在");
        return statuses.get(0);
    }

    public Permission permission(String cid, Long user) {
        String status = lock(cid);
        if (user == null) return DENIED;
        boolean active = "ACTIVE".equals(status);
        var participants = ConversationIds.parsePrivate(cid);
        if (participants.isPresent()) {
            var pair = participants.get();
            if (!pair.contains(user)) return DENIED;
            long peer = pair.peerOf(user);
            var relations = jdbc.queryForList("""
                    SELECT user_id,is_blocked FROM friendship
                    WHERE (user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)
                    ORDER BY user_id FOR UPDATE
                    """, user, peer, peer, user);
            boolean ownAllowed = false, peerBlocked = false;
            for (var relation : relations) {
                long owner = ((Number) relation.get("user_id")).longValue();
                Object blocked = relation.get("is_blocked");
                int value = blocked == null ? -1 : blocked instanceof Boolean flag
                        ? (flag ? 1 : 0) : ((Number) blocked).intValue();
                if (owner == user) ownAllowed = value == 0;
                else peerBlocked = value == 1;
            }
            return new Permission(true, active && ownAllowed && !peerBlocked);
        }
        var group = ConversationIds.parseGroup(cid);
        if (group.isPresent()) {
            var members = jdbc.query("SELECT mute_until FROM group_member WHERE group_id=? AND user_id=? FOR UPDATE",
                    (rs, n) -> rs.getObject("mute_until", LocalDateTime.class), group.get(), user);
            if (members.isEmpty()) return DENIED;
            LocalDateTime until = members.get(0);
            boolean expired = until != null && until.isBefore(LocalDateTime.now());
            return new Permission(true, active && (until == null || expired), active && expired ? until : null);
        }
        var room = ConversationIds.parseTemporary(cid);
        if (room.isEmpty()) return DENIED;
        var rooms = jdbc.query("SELECT status,expires_at FROM temporary_room WHERE id=? FOR UPDATE",
                (rs, n) -> new Room(rs.getString("status"), rs.getObject("expires_at", LocalDateTime.class)), room.get());
        if (rooms.isEmpty() || "DESTROYED".equals(status) || "DESTROYED".equals(rooms.get(0).status())) return DENIED;
        var roles = jdbc.queryForList("""
                SELECT role FROM conversation_member WHERE conversation_id=? AND user_id=? AND left_time IS NULL FOR UPDATE
                """, String.class, cid, user);
        if (roles.isEmpty()) return DENIED;
        Room current = rooms.get(0);
        boolean expired = current.expiresAt() != null && !current.expiresAt().isAfter(LocalDateTime.now());
        return new Permission(true, active && "ACTIVE".equals(current.status()) && current.expiresAt() != null
                && !expired && !"READ_ONLY".equals(roles.get(0)), null,
                active && "ACTIVE".equals(current.status()) && expired ? current.expiresAt() : null);
    }

    public List<Long> recipients(String cid) {
        lock(cid);
        var pair = ConversationIds.parsePrivate(cid);
        if (pair.isPresent()) return List.of(pair.get().firstUserId(), pair.get().secondUserId());
        var group = ConversationIds.parseGroup(cid);
        if (group.isPresent()) return jdbc.queryForList(
                "SELECT user_id FROM group_member WHERE group_id=? ORDER BY user_id FOR UPDATE", Long.class, group.get());
        return jdbc.queryForList("""
                SELECT user_id FROM conversation_member WHERE conversation_id=? AND left_time IS NULL ORDER BY user_id FOR UPDATE
                """, Long.class, cid);
    }
    private record Room(String status, LocalDateTime expiresAt) {}
}
