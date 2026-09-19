package com.lanchat.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Reads counter and facts from one database view; never hides a hole as an empty page. */
@Service
public class MutationStreamReader {
    private final JdbcTemplate jdbc;
    public MutationStreamReader(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Stream(String epoch,long floor,long latest) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Record(int recordVersion,String eventId,String streamEpoch,String cursor,MutationFact.Type type,
                         String conversationId,String messageId,String objectVersion,String accessVersion,
                         Boolean readAllowed,Boolean sendAllowed,Boolean rebuildConversation,
                         MutationFact.Reason reason,String committedAt) {}
    public record MutationPage(List<Record> records,String fromExclusive,String through,String nextCursor,
                       boolean hasMore,String floor,String latest,String streamEpoch) {}

    public static long cursor(String value) {
        if (value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new RecoveryFault(400,"INVALID_CURSOR");
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new RecoveryFault(400,"INVALID_CURSOR"); }
    }

    public Stream stream(long user) {
        if (user<=0) throw new RecoveryFault(401,"AUTH_REQUIRED");
        var streams=jdbc.query("SELECT stream_epoch,floor_cursor,latest_cursor FROM recovery_user_stream WHERE user_id=?",
                (rs,row)->new Stream(rs.getString(1),rs.getLong(2),rs.getLong(3)),user);
        if (streams.size()!=1) throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
        var stream=streams.get(0);
        if (!uuid(stream.epoch()) || stream.floor()<0 || stream.latest()<stream.floor()) throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
        return stream;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MutationPage read(long user,String epoch,String afterValue,String throughValue,int limit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ).equals(
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Recovery reads require a repeatable-read transaction");
        }
        long after=cursor(afterValue),through=cursor(throughValue);
        if (limit<1 || limit>200 || after>through) throw new RecoveryFault(400,"RANGE_MISMATCH");
        Stream stream=stream(user);
        if (!stream.epoch().equals(epoch)) throw new RecoveryFault(409,"STREAM_RESET");
        if (after<stream.floor()) throw new RecoveryFault(409,"CURSOR_EXPIRED");
        if (through>stream.latest()) throw new RecoveryFault(409,"CURSOR_AHEAD");
        List<Record> records;
        try {
            records=jdbc.query("""
                    SELECT event_id,stream_epoch,mutation_cursor,mutation_type,conversation_id,message_id,
                    object_version,access_version,read_allowed,send_allowed,rebuild_conversation,reason,committed_at
                    FROM recovery_mutation WHERE user_id=? AND stream_epoch=? AND mutation_cursor>? AND mutation_cursor<=?
                    ORDER BY mutation_cursor LIMIT ?
                    """,(rs,row)->{
                var fact=new MutationFact(MutationFact.Type.valueOf(rs.getString("mutation_type")),rs.getString("conversation_id"),
                        rs.getString("message_id"),rs.getObject("object_version",Long.class),rs.getObject("access_version",Long.class),
                        rs.getObject("read_allowed",Boolean.class),rs.getObject("send_allowed",Boolean.class),
                        rs.getObject("rebuild_conversation",Boolean.class),rs.getString("reason")==null ? null : MutationFact.Reason.valueOf(rs.getString("reason")));
                if(!uuid(rs.getString("event_id")) || !uuid(rs.getString("stream_epoch"))) throw new IllegalArgumentException("Invalid mutation identity");
                return new Record(1,rs.getString("event_id"),rs.getString("stream_epoch"),Long.toString(rs.getLong("mutation_cursor")),
                        fact.type(),fact.conversationId(),fact.messageId(),decimal(fact.objectVersion()),decimal(fact.accessVersion()),
                        fact.readAllowed(),fact.sendAllowed(),fact.rebuildConversation(),fact.reason(),
                        rs.getObject("committed_at",LocalDateTime.class).atOffset(ZoneOffset.UTC)
                                .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")));
            },user,epoch,after,through,limit);
        } catch (IllegalArgumentException | NullPointerException corrupt) {
            throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
        }
        long next=after;
        for (Record record:records) {
            if (next==Long.MAX_VALUE || cursor(record.cursor())!=next+1) throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
            next++;
        }
        if (records.size()<limit && next<through) throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
        return new MutationPage(List.copyOf(records),afterValue,throughValue,Long.toString(next),next<through,
                Long.toString(stream.floor()),Long.toString(stream.latest()),stream.epoch());
    }
    private String decimal(Long value) { return value==null?null:Long.toString(value); }
    private static boolean uuid(String value) {return value!=null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
}
