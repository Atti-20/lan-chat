package com.lanchat.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

/** Projects immutable manifest positions through current authorization and terminal state. */
@Service
public class RecoverySnapshotPages {
    private final JdbcTemplate jdbc;
    private final ConversationWriteGuard guard;
    private final MutationStreamReader reader;
    public RecoverySnapshotPages(JdbcTemplate jdbc,ConversationWriteGuard guard,MutationStreamReader reader) {
        this.jdbc=jdbc;this.guard=guard;this.reader=reader;
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageDetails(Long fromUserId,String clientMsgId,String contentType,LocalDateTime createTime,
                                 Integer isBurn,Integer burnDuration,String replyToId,String mentionUserIds) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String kind,String conversationId,String messageId,String objectVersion,String accessVersion,
                       Boolean readAllowed,Boolean sendAllowed,String messageSequenceAtH,String messageSequence,
                       String state,String content,MessageDetails details) {}
    public record SnapshotPage(String snapshotId,String boundary,List<Item> items,String nextPageToken,boolean snapshotComplete) {}
    private record Session(String epoch,long start,LocalDateTime expires) {}
    private record Snapshot(String id,long count,long served) {}
    private record Header(long position,String kind,String cid,String message,Long version,long sequence,String state) {}
    private record Access(long version,boolean read,boolean send) {}
    private record State(String cid,long sequence,long version,String state) {}
    private record Body(String content,Integer recalled,Integer status,MessageDetails details) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=30)
    public SnapshotPage page(long user,String origin,String id,String token,int limit) {
        RecoveryResumeSessions.validateCanonicalOwner(user,origin);
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Snapshot projection requires a write transaction");
        }
        if(limit<1 || limit>200) throw new RecoveryFault(400,"RANGE_MISMATCH");
        var sessions=jdbc.query("SELECT stream_epoch,start_cursor,expires_at FROM recovery_session WHERE id=? AND user_id=? AND origin=? FOR UPDATE",
                (rs,n)->new Session(rs.getString(1),rs.getLong(2),rs.getObject(3,LocalDateTime.class)),id,user,origin);
        if(sessions.isEmpty()) throw new RecoveryFault(404,"RECOVERY_NOT_FOUND");
        var session=sessions.get(0);validate(user,session);
        var snapshots=jdbc.query("SELECT snapshot_id,item_count,served_through FROM recovery_snapshot WHERE session_id=? FOR UPDATE",
                (rs,n)->new Snapshot(rs.getString(1),rs.getLong(2),rs.getLong(3)),id);
        if(snapshots.size()!=1) throw new RecoveryFault(400,"RANGE_MISMATCH");
        var snapshot=snapshots.get(0);long position=0;
        if(token!=null) {
            var positions=jdbc.queryForList("SELECT position FROM recovery_snapshot_item WHERE session_id=? AND page_token=?",Long.class,id,token);
            if(positions.size()!=1) throw new RecoveryFault(400,"INVALID_PAGE_TOKEN");
            position=positions.get(0);
        }
        if(position>snapshot.served()) throw new RecoveryFault(400,"INVALID_PAGE_TOKEN");
        var headers=jdbc.query("""
                SELECT position,kind,conversation_id,message_id,object_version,message_sequence,state
                FROM recovery_snapshot_item WHERE session_id=? AND position>=? ORDER BY position LIMIT ?
                """,(rs,n)->new Header(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getObject(5,Long.class),rs.getLong(6),rs.getString(7)),id,position,limit);
        long expected=position;
        for(var header:headers) if(header.position()!=expected++) unavailable();
        if(expected<snapshot.count() && headers.size()<limit) unavailable();
        var cids=new TreeSet<String>();for(var header:headers)cids.add(header.cid());
        // All conversation locks precede message rows, following the business writers' lock order.
        var missingConversations=new java.util.HashSet<String>();
        for(String cid:cids) {
            try {guard.lock(cid);} catch(IllegalArgumentException missing) {missingConversations.add(cid);}
        }
        var access=new HashMap<String,Access>();
        for(String cid:cids) {
            var permission=missingConversations.contains(cid)?new ConversationWriteGuard.Permission(false,false):guard.permission(cid,user);
            var values=jdbc.query("SELECT access_version,read_allowed,send_allowed FROM recovery_access_state WHERE conversation_id=? AND user_id=? FOR UPDATE",
                    (rs,n)->new Access(rs.getLong(1),rs.getBoolean(2),rs.getBoolean(3)),cid,user);
            if(values.size()!=1) unavailable();
            var value=values.get(0);
            if(value.version()<=0 || value.read()!=permission.readAllowed() || value.send()!=permission.sendAllowed()) unavailable();
            Long atSnapshot=jdbc.queryForObject("SELECT access_version FROM recovery_snapshot_item WHERE session_id=? AND kind='CONVERSATION' AND conversation_id=?",Long.class,id,cid);
            if(atSnapshot==null || value.version()<atSnapshot) unavailable();
            access.put(cid,value);
        }
        var items=new ArrayList<Item>();
        for(var header:headers) {
            var value=access.get(header.cid());
            if(!value.read() || "CONVERSATION".equals(header.kind())) {
                // Loss of access invalidates the entire conversation; never invent a global message tombstone.
                items.add(new Item("CONVERSATION",header.cid(),null,null,Long.toString(value.version()),value.read(),value.send(),
                        value.read()?Long.toString(header.sequence()):null,null,null,null,null));
                continue;
            }
            if(!"MESSAGE".equals(header.kind())) unavailable();
            var states=jdbc.query("SELECT conversation_id,message_sequence,object_version,state FROM recovery_message_state WHERE message_id=? FOR UPDATE",
                    (rs,n)->new State(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getString(4)),header.message());
            if(states.size()!=1) unavailable();
            var state=states.get(0);
            if(!state.cid().equals(header.cid()) || state.sequence()!=header.sequence() || state.version()<header.version()
                    || !List.of("NORMAL","RECALLED","BURNED","UNAVAILABLE").contains(state.state())
                    || (!"NORMAL".equals(header.state()) && !header.state().equals(state.state()) && !"UNAVAILABLE".equals(state.state()))
                    || (state.version()==header.version() && !state.state().equals(header.state()))) unavailable();
            String content=null;
            MessageDetails details=null;
            if("NORMAL".equals(state.state())) {
                var bodies=jdbc.query("SELECT content,is_recalled,status,from_user_id,client_msg_id,type,create_time,is_burn,burn_duration,reply_to_id,mention_user_ids FROM chat_message WHERE message_id=? AND conversation_id=? FOR UPDATE",
                        (rs,n)->new Body(rs.getString(1),rs.getObject(2,Integer.class),rs.getObject(3,Integer.class),
                                new MessageDetails(rs.getObject(4,Long.class),rs.getString(5),rs.getString(6),rs.getObject(7,LocalDateTime.class),
                                        rs.getObject(8,Integer.class),rs.getObject(9,Integer.class),rs.getString(10),rs.getString(11))),header.message(),header.cid());
                if(bodies.size()!=1 || bodies.get(0).recalled()==null || bodies.get(0).recalled()!=0
                        || bodies.get(0).status()==null || bodies.get(0).status()<0 || bodies.get(0).status()>1) unavailable();
                content=bodies.get(0).content();
                details=bodies.get(0).details();
                if(content==null || details.fromUserId()==null || details.fromUserId()<=0 || details.contentType()==null
                        || details.contentType().isBlank() || details.createTime()==null || details.isBurn()==null
                        || (details.isBurn()!=0 && details.isBurn()!=1)) unavailable();
            }
            items.add(new Item("MESSAGE",header.cid(),header.message(),Long.toString(state.version()),null,null,null,null,
                    Long.toString(state.sequence()),state.state(),content,details));
        }
        validate(user,session);
        boolean complete=expected==snapshot.count();
        String next=complete?null:jdbc.queryForObject("SELECT page_token FROM recovery_snapshot_item WHERE session_id=? AND position=?",String.class,id,expected);
        jdbc.update("UPDATE recovery_snapshot SET served_through=GREATEST(served_through,?),page_complete=page_complete OR ? WHERE session_id=?",expected,complete,id);
        return new SnapshotPage(snapshot.id(),Long.toString(session.start()),List.copyOf(items),next,complete);
    }
    private void validate(long user,Session session) {
        if(!session.expires().isAfter(LocalDateTime.now(ZoneOffset.UTC))) throw new RecoveryFault(410,"SESSION_EXPIRED");
        var stream=reader.stream(user);
        if(!stream.epoch().equals(session.epoch())) throw new RecoveryFault(409,"STREAM_RESET");
        if(stream.floor()>session.start()) throw new RecoveryFault(409,"CURSOR_EXPIRED");
    }
    private static void unavailable() {throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");}
}
