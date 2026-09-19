package com.lanchat.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

/** Resume-session core. REST exposure waits for the shared rebuild/availability gate. */
@Service
public class RecoveryResumeSessions {
    private final JdbcTemplate jdbc;
    private final MutationStreamReader reader;
    private static final SecureRandom random=new SecureRandom();
    public RecoveryResumeSessions(JdbcTemplate jdbc,MutationStreamReader reader) { this.jdbc=jdbc;this.reader=reader; }
    public record Session(String recoveryId,String streamEpoch,String mode,String startCursor,String floor,String latest,String expiresAt) {}
    public record Cut(String through,String floor,String streamEpoch) {}
    public record Ready(boolean ready,String acceptedCursor,String latest,String streamEpoch) {}
    private record Row(String id,String epoch,long start,long floor,long latest,Long cut,boolean nextCut,LocalDateTime expires,String requestHash,String mode) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Session open(long user,String origin,String epoch,String cursor,String idempotencyKey) {
        requireWriteTransaction();
        validateOwner(user,origin);
        long start=MutationStreamReader.cursor(cursor);
        String key=idempotencyKey==null?null:hash(validKey(idempotencyKey));
        String input=hash("resume\n"+epoch+"\n"+cursor);
        // Serializes capacity, pin creation, and a retention worker advancing this user's floor.
        var streams=jdbc.query("SELECT stream_epoch,floor_cursor,latest_cursor FROM recovery_user_stream WHERE user_id=? FOR UPDATE",
                (rs,n)->new MutationStreamReader.Stream(rs.getString(1),rs.getLong(2),rs.getLong(3)),user);
        if (streams.size()!=1) throw new RecoveryFault(409,"STREAM_RESET");
        var stream=streams.get(0);
        if (!stream.epoch().equals(epoch)) throw new RecoveryFault(409,"STREAM_RESET");
        if (start<stream.floor()) throw new RecoveryFault(409,"CURSOR_EXPIRED");
        if (start>stream.latest()) throw new RecoveryFault(409,"CURSOR_AHEAD");
        Row existing=key==null?null:byKey(user,origin,key); // READ_COMMITTED sees a concurrent creator's committed result.
        if (existing!=null) return cached(existing,input);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM recovery_session WHERE user_id=? AND expires_at>UTC_TIMESTAMP(6)",Long.class,user)>=20) {
            throw new RecoveryFault(503,"RECOVERY_CAPACITY");
        }
        byte[] bytes=new byte[32];random.nextBytes(bytes);String id=HexFormat.of().formatHex(bytes);
        jdbc.update("""
                INSERT INTO recovery_session(id,user_id,origin,stream_epoch,mode,start_cursor,initial_floor,initial_latest,
                expires_at,idempotency_hash,request_hash) VALUES (?,?,?,?,'resume',?,?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE),?,?)
                """,id,user,origin,epoch,start,stream.floor(),stream.latest(),key,input);
        return view(owned(user,origin,id,false));
    }

    @Transactional
    public Cut cut(long user,String origin,String id) {
        requireWriteTransaction();
        Row row=owned(user,origin,id,true);
        var stream=current(user,row);
        long cut=row.cut()==null || row.nextCut() ? stream.latest() : row.cut();
        jdbc.update("UPDATE recovery_session SET cut_cursor=?,next_cut_allowed=FALSE WHERE id=?",cut,id);
        return new Cut(Long.toString(cut),Long.toString(stream.floor()),row.epoch());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MutationStreamReader.MutationPage mutations(long user,String origin,String id,String after,String through,int limit) {
        Row row=owned(user,origin,id,false);
        if (row.cut()==null || MutationStreamReader.cursor(through)!=row.cut() || MutationStreamReader.cursor(after)<row.start()) {
            throw new RecoveryFault(400,"RANGE_MISMATCH");
        }
        return reader.read(user,row.epoch(),after,through,limit);
    }

    @Transactional
    public Ready ready(long user,String origin,String id,String appliedCursor) {
        return ready(user,origin,id,appliedCursor,false);
    }

    @Transactional
    public Ready ready(long user,String origin,String id,String appliedCursor,boolean snapshotComplete) {
        requireWriteTransaction();
        Row row=owned(user,origin,id,true);
        var snapshots=jdbc.queryForList("SELECT page_complete,served_through,item_count FROM recovery_snapshot WHERE session_id=? FOR UPDATE",id);
        if (!"resume".equals(row.mode()) && snapshots.isEmpty()) throw new RecoveryFault(410,"SNAPSHOT_EXPIRED");
        if (!snapshots.isEmpty() && (!snapshotComplete || !Boolean.TRUE.equals(snapshots.get(0).get("page_complete"))
                || ((Number)snapshots.get(0).get("served_through")).longValue()!=((Number)snapshots.get(0).get("item_count")).longValue())) {
            throw new RecoveryFault(409,"SNAPSHOT_INCOMPLETE");
        }
        long applied=MutationStreamReader.cursor(appliedCursor);
        if (row.cut()==null || applied!=row.cut()) throw new RecoveryFault(400,"RANGE_MISMATCH");
        var stream=current(user,row);
        jdbc.update("UPDATE recovery_session SET next_cut_allowed=TRUE WHERE id=?",id);
        return new Ready(stream.latest()==applied,appliedCursor,Long.toString(stream.latest()),row.epoch());
    }

    @Transactional
    public void release(long user,String origin,String id) {
        requireWriteTransaction();
        validateOwner(user,origin);
        if (jdbc.update("DELETE FROM recovery_session WHERE id=? AND user_id=? AND origin=?",id,user,origin)!=1) {
            throw new RecoveryFault(404,"RECOVERY_NOT_FOUND");
        }
    }

    private MutationStreamReader.Stream current(long user,Row row) {
        var streams=jdbc.query("SELECT stream_epoch,floor_cursor,latest_cursor FROM recovery_user_stream WHERE user_id=? FOR UPDATE",
                (rs,n)->new MutationStreamReader.Stream(rs.getString(1),rs.getLong(2),rs.getLong(3)),user);
        if (streams.size()!=1 || !streams.get(0).epoch().equals(row.epoch())) throw new RecoveryFault(409,"STREAM_RESET");
        if (row.start()<streams.get(0).floor()) throw new RecoveryFault(409,"CURSOR_EXPIRED");
        return streams.get(0);
    }
    private Row owned(long user,String origin,String id,boolean lock) {
        validateOwner(user,origin);
        var rows=rows("id=? AND user_id=? AND origin=?"+(lock?" FOR UPDATE":""),id,user,origin);
        if (rows.isEmpty()) throw new RecoveryFault(404,"RECOVERY_NOT_FOUND");
        Row row=rows.get(0);alive(row);return row;
    }
    private Row byKey(long user,String origin,String key) {
        var rows=rows("user_id=? AND origin=? AND idempotency_hash=?",user,origin,key);
        return rows.isEmpty()?null:rows.get(0);
    }
    private List<Row> rows(String condition,Object... args) {
        return jdbc.query("SELECT id,stream_epoch,start_cursor,initial_floor,initial_latest,cut_cursor,next_cut_allowed,expires_at,request_hash,mode FROM recovery_session WHERE "+condition,
                (rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getObject(6,Long.class),
                        rs.getBoolean(7),rs.getObject(8,LocalDateTime.class),rs.getString(9),rs.getString(10)),args);
    }
    private Session cached(Row row,String input) {
        if (!row.requestHash().equals(input)) throw new RecoveryFault(409,"IDEMPOTENCY_CONFLICT");
        alive(row);return view(row);
    }
    private void alive(Row row) {
        if (!row.expires().isAfter(LocalDateTime.now(ZoneOffset.UTC))) throw new RecoveryFault(410,"SESSION_EXPIRED");
    }
    private Session view(Row row) {
        return new Session(row.id(),row.epoch(),"resume",Long.toString(row.start()),Long.toString(row.floor()),Long.toString(row.latest()),row.expires().atOffset(ZoneOffset.UTC).toString());
    }
    private void validateOwner(long user,String origin) {
        validateCanonicalOwner(user,origin);
    }
    static void validateCanonicalOwner(long user,String origin) {
        if (user<=0) throw new RecoveryFault(401,"AUTH_REQUIRED");
        if (origin==null || origin.length()>255) throw new RecoveryFault(400,"INVALID_ORIGIN");
        try {
            URI uri=URI.create(origin);
            String scheme=uri.getScheme(),host=uri.getHost();
            int port=uri.getPort();
            if (!("http".equals(scheme) || "https".equals(scheme)) || host==null
                    || !host.equals(host.toLowerCase(java.util.Locale.ROOT))
                    || uri.getRawUserInfo()!=null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || port==0 || port>65535 || ("http".equals(scheme) && port==80)
                    || ("https".equals(scheme) && port==443)
                    || !origin.equals(scheme+"://"+host+(port<0?"":":"+port))) {
                throw new IllegalArgumentException("Noncanonical origin");
            }
        } catch (IllegalArgumentException invalid) {
            throw new RecoveryFault(400,"INVALID_ORIGIN");
        }
    }
    static String validKey(String key) {
        if (key.isBlank() || key.length()>128) throw new RecoveryFault(400,"INVALID_IDEMPOTENCY_KEY");return key;
    }
    private void requireWriteTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Recovery sessions require a write transaction");
        }
    }
    static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
