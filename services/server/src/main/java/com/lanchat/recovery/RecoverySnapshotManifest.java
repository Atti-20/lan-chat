package com.lanchat.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Durable, body-free manifest. Current-state page projection is a separate authorization step. */
@Service
public class RecoverySnapshotManifest {
    private final JdbcTemplate jdbc;
    private final MutationStreamReader reader;
    private static final SecureRandom random=new SecureRandom();
    public RecoverySnapshotManifest(JdbcTemplate jdbc,MutationStreamReader reader) {this.jdbc=jdbc;this.reader=reader;}
    public record Snapshot(String recoveryId,String snapshotId,String streamEpoch,String startCursor,
                           String floor,String latest,String expiresAt,long itemCount,String mode) {}
    private record Item(String kind,String cid,String message,Long objectVersion,Long accessVersion,
                        long sequence,String state,Boolean read,Boolean send) {}
    private record BodyHeader(String message,String cid,Long sequence,Integer recalled,Integer status) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=60)
    public Snapshot create(long user,String origin) {
        return create(user,origin,null);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=60)
    public Snapshot create(long user,String origin,String idempotencyKey) {
        return create(user,origin,idempotencyKey,null);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=60)
    public Snapshot create(long user,String origin,String idempotencyKey,List<String> conversationIds) {
        RecoveryResumeSessions.validateCanonicalOwner(user,origin);
        List<String> selected=selection(conversationIds);
        String key=idempotencyKey==null?null:RecoveryResumeSessions.hash(RecoveryResumeSessions.validKey(idempotencyKey));
        String input=RecoveryResumeSessions.hash(selected==null?"rebuild\n":"rebuild-conversations\n"+String.join("\n",selected));
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ).equals(
                org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Snapshot requires its own repeatable-read transaction");
        }
        // No consistent SELECT before this lock. It serializes pin publication against retention.
        jdbc.update("INSERT INTO recovery_user_stream(user_id,stream_epoch) VALUES (?,?) ON DUPLICATE KEY UPDATE user_id=user_id",
                user,UUID.randomUUID().toString());
        jdbc.queryForObject("SELECT user_id FROM recovery_user_stream WHERE user_id=? FOR UPDATE",Long.class,user);
        // This ordinary read creates S: H and all following directory/message reads share S.
        var stream=reader.stream(user);
        if(key!=null) {
            var existing=jdbc.queryForList("SELECT id,request_hash,stream_epoch,start_cursor,initial_floor,initial_latest,expires_at,mode FROM recovery_session WHERE user_id=? AND origin=? AND idempotency_hash=?",user,origin,key);
            if(!existing.isEmpty()) {
                var saved=existing.get(0);
                if(!input.equals(saved.get("request_hash"))) throw new RecoveryFault(409,"IDEMPOTENCY_CONFLICT");
                if(!stream.epoch().equals(saved.get("stream_epoch"))) throw new RecoveryFault(409,"STREAM_RESET");
                LocalDateTime expires=jdbc.queryForObject("SELECT expires_at FROM recovery_session WHERE id=?",LocalDateTime.class,saved.get("id"));
                if(!expires.isAfter(LocalDateTime.now(ZoneOffset.UTC))) throw new RecoveryFault(410,"SESSION_EXPIRED");
                var snapshots=jdbc.queryForList("SELECT snapshot_id,item_count FROM recovery_snapshot WHERE session_id=?",saved.get("id"));
                if(snapshots.size()!=1) throw new RecoveryFault(410,"SNAPSHOT_EXPIRED");
                return new Snapshot((String)saved.get("id"),(String)snapshots.get(0).get("snapshot_id"),stream.epoch(),
                        saved.get("start_cursor").toString(),saved.get("initial_floor").toString(),saved.get("initial_latest").toString(),
                        expires.atOffset(ZoneOffset.UTC).toString(),((Number)snapshots.get(0).get("item_count")).longValue(),(String)saved.get("mode"));
            }
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM recovery_session WHERE user_id=? AND expires_at>UTC_TIMESTAMP(6)",Long.class,user)>=20) {
            throw new RecoveryFault(503,"RECOVERY_CAPACITY");
        }
        List<Item> items=new ArrayList<>();
        List<java.util.Map<String,Object>> directory=null;
        String mode=selected==null?"rebuild":"rebuild-conversations";
        if(selected!=null) {
            var args=new ArrayList<Object>();args.add(user);args.addAll(selected);
            directory=jdbc.queryForList("""
                    SELECT a.conversation_id AS id,c.last_sequence,a.access_version,a.read_allowed,a.send_allowed,
                    cm.id AS member_id,cm.left_time
                    FROM recovery_access_state a LEFT JOIN conversation c ON c.id=a.conversation_id
                    LEFT JOIN conversation_member cm ON cm.conversation_id=a.conversation_id AND cm.user_id=a.user_id
                    WHERE a.user_id=? AND a.conversation_id IN (
                    """+String.join(",",java.util.Collections.nCopies(selected.size(),"?"))+") ORDER BY a.conversation_id",args.toArray());
            // No durable access version exists for an unknown ID. Use the approved whole-account
            // rebuild fallback rather than fabricate a revocation version or silently skip that ID.
            if(directory.size()!=selected.size()) {directory=null;mode="rebuild";}
            else for(var entry:directory) {
                if(booleanValue(entry.get("read_allowed")) && (entry.get("member_id")==null || entry.get("left_time")!=null)) unavailable();
            }
        }
        if(directory==null) directory=jdbc.queryForList("""
                SELECT c.id,c.last_sequence,a.access_version,a.read_allowed,a.send_allowed
                FROM conversation_member cm JOIN conversation c ON c.id=cm.conversation_id
                LEFT JOIN recovery_access_state a ON a.conversation_id=c.id AND a.user_id=cm.user_id
                WHERE cm.user_id=? AND cm.left_time IS NULL ORDER BY c.id LIMIT 101
                """,user);
        if (directory.size()>100) throw new RecoveryFault(503,"RECOVERY_CAPACITY");
        for(var entry:directory) {
            if(entry.get("access_version")==null) unavailable();
            String cid=(String)entry.get("id");
            boolean read=booleanValue(entry.get("read_allowed")),send=booleanValue(entry.get("send_allowed"));
            if(!read && "rebuild".equals(mode)) continue;
            long version=((Number)entry.get("access_version")).longValue();
            if(read && entry.get("last_sequence")==null) unavailable();
            long sequence=read?((Number)entry.get("last_sequence")).longValue():0;
            if(version<=0 || sequence<0) unavailable();
            items.add(new Item("CONVERSATION",cid,null,null,version,sequence,null,read,send));
        }
        // Materialize IDs in Java from consistent SELECTs; INSERT ... SELECT can use a current read in MySQL.
        var conversations=List.copyOf(items);int messages=0;
        for(var conversation:conversations) {
            if(!conversation.read()) continue;
            int remaining=200000-messages;
            var rows=jdbc.query("""
                    SELECT message_id,object_version,message_sequence,state FROM recovery_message_state
                    WHERE conversation_id=? ORDER BY message_sequence,message_id LIMIT ?
                    """,(rs,n)->new Item("MESSAGE",conversation.cid(),rs.getString(1),rs.getLong(2),null,
                    rs.getLong(3),rs.getString(4),null,null),conversation.cid(),remaining+1);
            messages+=rows.size();if(messages>200000) throw new RecoveryFault(503,"RECOVERY_CAPACITY");
            for(var row:rows) {
                if(row.objectVersion()<=0 || row.sequence()<=0 || row.sequence()>conversation.sequence()
                        || !List.of("NORMAL","RECALLED","BURNED","UNAVAILABLE").contains(row.state())) unavailable();
            }
            // Both reads share S. Compare bounded headers in memory: joining the
            // binary recovery IDs to legacy collations can turn orphan detection
            // into repeated full scans at the retained-history capacity limit.
            var states=new java.util.HashMap<String,Item>();
            for(var row:rows) states.put(row.message(),row);
            var bodies=jdbc.query("""
                    SELECT message_id,conversation_id,sequence,is_recalled,status
                    FROM chat_message WHERE conversation_id=? LIMIT ?
                    """,(rs,n)->new BodyHeader(rs.getString(1),rs.getString(2),rs.getObject(3,Long.class),
                    rs.getObject(4,Integer.class),rs.getObject(5,Integer.class)),conversation.cid(),remaining+1);
            if(bodies.size()>remaining) throw new RecoveryFault(503,"RECOVERY_CAPACITY");
            var seenBodies=new java.util.HashSet<String>();
            for(var body:bodies) {
                var state=states.get(body.message());
                if(state==null || !conversation.cid().equals(body.cid()) || !seenBodies.add(body.message())
                        || body.sequence()==null || body.sequence()!=state.sequence()
                        || body.recalled()==null || body.status()==null
                        || (body.recalled()==1 && !"RECALLED".equals(state.state()))
                        || (body.recalled()==0 && body.status()==2 && !"BURNED".equals(state.state()))
                        || (body.recalled()==0 && body.status()!=2 && !"NORMAL".equals(state.state()))) unavailable();
            }
            for(var row:rows) if("NORMAL".equals(row.state()) && !seenBodies.contains(row.message())) unavailable();
            items.addAll(rows);
        }
        String id=token(),snapshot=token();
        jdbc.update("""
                INSERT INTO recovery_session(id,user_id,origin,stream_epoch,mode,start_cursor,initial_floor,initial_latest,
                expires_at,request_hash,idempotency_hash) VALUES (?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE),?,?)
                """,id,user,origin,stream.epoch(),mode,stream.latest(),stream.floor(),stream.latest(),input,key);
        jdbc.update("INSERT INTO recovery_snapshot(session_id,snapshot_id,item_count) VALUES (?,?,?)",id,snapshot,items.size());
        for(int offset=0;offset<items.size();offset+=500) {
            var values=new java.util.StringJoiner(",");
            List<Object> arguments=new ArrayList<>();
            for(int i=offset;i<Math.min(offset+500,items.size());i++) {
                var row=items.get(i);
                values.add("(?,?,?,?,?,?,?,?,?,?,?,?)");
                java.util.Collections.addAll(arguments,id,i,token(),row.kind(),row.cid(),row.message(),row.objectVersion(),row.accessVersion(),
                        row.sequence(),row.state(),row.read(),row.send());
            }
            // A bounded multi-row statement avoids 200k driver round trips even
            // when the deployment does not enable rewriteBatchedStatements.
            jdbc.update("INSERT INTO recovery_snapshot_item VALUES "+values,arguments.toArray());
        }
        LocalDateTime expiry=jdbc.queryForObject("SELECT expires_at FROM recovery_session WHERE id=?",LocalDateTime.class,id);
        return new Snapshot(id,snapshot,stream.epoch(),Long.toString(stream.latest()),Long.toString(stream.floor()),
                Long.toString(stream.latest()),expiry.atOffset(ZoneOffset.UTC).toString(),items.size(),mode);
    }
    private static List<String> selection(List<String> ids) {
        if(ids==null)return null;
        if(ids.isEmpty() || ids.size()>100)throw new RecoveryFault(400,"INVALID_CONVERSATIONS");
        var result=new java.util.TreeSet<String>();
        for(String cid:ids) {
            if(cid==null || cid.length()>128 || !cid.matches("(?:group|temporary):[1-9][0-9]*|private:[1-9][0-9]*:[1-9][0-9]*")
                    || (com.lanchat.common.ConversationIds.parsePrivate(cid).isEmpty()
                    && com.lanchat.common.ConversationIds.parseGroup(cid).isEmpty()
                    && com.lanchat.common.ConversationIds.parseTemporary(cid).isEmpty()))throw new RecoveryFault(400,"INVALID_CONVERSATIONS");
            result.add(cid);
        }
        return List.copyOf(result);
    }
    private static String token() {byte[] value=new byte[32];random.nextBytes(value);return HexFormat.of().formatHex(value);}
    private static boolean booleanValue(Object value) {
        if(value instanceof Boolean bool) return bool;
        if(value instanceof Number number) return number.intValue()!=0;
        throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
    }
    private static void unavailable() {throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");}
}
