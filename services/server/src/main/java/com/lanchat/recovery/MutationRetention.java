package com.lanchat.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded prefix collection, serialized with append and new session pins. */
@Service
public class MutationRetention {
    private final JdbcTemplate jdbc;
    public MutationRetention(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Result(int deleted,String floor,String latest) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Result prune(long user,int limit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Retention requires a write transaction");
        }
        if (user<=0 || limit<1 || limit>2000) throw new IllegalArgumentException("Invalid retention batch");
        var streams=jdbc.query("SELECT stream_epoch,floor_cursor,latest_cursor FROM recovery_user_stream WHERE user_id=? FOR UPDATE",
                (rs,n)->new MutationStreamReader.Stream(rs.getString(1),rs.getLong(2),rs.getLong(3)),user);
        if (streams.size()!=1) throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
        var stream=streams.get(0);
        // Never lock sessions after the stream: cut/ready lock session then stream.
        // Open holds this stream lock before inserting a pin; READ_COMMITTED sees that commit.
        Long pin=jdbc.queryForObject("""
                SELECT MIN(start_cursor) FROM recovery_session
                WHERE user_id=? AND stream_epoch=? AND expires_at>UTC_TIMESTAMP(6)
                """,Long.class,user,stream.epoch());
        long upper=pin==null?stream.latest():Math.min(pin,stream.latest());
        var rows=jdbc.query("""
                SELECT mutation_cursor,committed_at<=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 DAY) AS old_enough
                FROM recovery_mutation WHERE user_id=? AND stream_epoch=? AND mutation_cursor>?
                AND mutation_cursor<=? ORDER BY mutation_cursor LIMIT ?
                """,(rs,n)->new Candidate(rs.getLong(1),rs.getBoolean(2)),user,stream.epoch(),stream.floor(),upper,limit);
        long floor=stream.floor();int count=0;
        for(var row:rows) {
            if (floor==Long.MAX_VALUE || row.cursor()!=floor+1) throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
            if (!row.oldEnough()) break;
            floor=row.cursor();count++;
        }
        if (count==rows.size() && rows.size()<limit && floor<upper) throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
        if(count>0) {
            int removed=jdbc.update("DELETE FROM recovery_mutation WHERE user_id=? AND stream_epoch=? AND mutation_cursor>? AND mutation_cursor<=?",
                    user,stream.epoch(),stream.floor(),floor);
            if(removed!=count) throw new RecoveryFault(503,"RECOVERY_UNAVAILABLE");
            // Hints for an expired prefix are obsolete: clients behind floor must rebuild.
            // Keep the dispatch backlog bounded without changing message/access tombstones.
            jdbc.update("DELETE FROM recovery_dispatch_outbox WHERE user_id=? AND stream_epoch=? AND mutation_cursor>? AND mutation_cursor<=?",
                    user,stream.epoch(),stream.floor(),floor);
            jdbc.update("UPDATE recovery_user_stream SET floor_cursor=? WHERE user_id=?",floor,user);
        }
        return new Result(count,Long.toString(floor),Long.toString(stream.latest()));
    }
    private record Candidate(long cursor,boolean oldEnough) {}
}
