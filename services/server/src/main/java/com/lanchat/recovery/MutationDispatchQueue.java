package com.lanchat.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Short database leases. Transport must run after claim commits, never under row locks. */
@Service
public class MutationDispatchQueue {
    private final JdbcTemplate jdbc;
    public MutationDispatchQueue(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Lease(long userId, String epoch, String cursor, String eventId, String token, int attempts) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Lease> claim(int limit) {
        requireTransaction();
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("Dispatch batch must be 1..200");
        var rows = jdbc.queryForList("""
                SELECT user_id,stream_epoch,mutation_cursor,event_id,attempts FROM recovery_dispatch_outbox
                WHERE (status='PENDING' AND next_retry_at<=UTC_TIMESTAMP(6))
                   OR (status='PROCESSING' AND lease_until<=UTC_TIMESTAMP(6))
                ORDER BY next_retry_at,user_id,mutation_cursor LIMIT ? FOR UPDATE SKIP LOCKED
                """, limit);
        var leases = new ArrayList<Lease>();
        for (var row : rows) {
            long user = ((Number)row.get("user_id")).longValue();
            String epoch = (String)row.get("stream_epoch");
            long cursor = ((Number)row.get("mutation_cursor")).longValue();
            int attempts = ((Number)row.get("attempts")).intValue();
            if (attempts < Integer.MAX_VALUE) attempts++;
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    UPDATE recovery_dispatch_outbox SET status='PROCESSING',attempts=?,lease_token=?,
                    lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND)
                    WHERE user_id=? AND stream_epoch=? AND mutation_cursor=?
                    """,attempts,token,user,epoch,cursor);
            leases.add(new Lease(user,epoch,Long.toString(cursor),(String)row.get("event_id"),token,attempts));
        }
        return List.copyOf(leases);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acknowledge(Lease lease) {
        requireTransaction();
        return jdbc.update("""
                UPDATE recovery_dispatch_outbox SET status='DISPATCHED',lease_token=NULL,lease_until=NULL
                WHERE user_id=? AND stream_epoch=? AND mutation_cursor=? AND status='PROCESSING'
                AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)
                """,lease.userId(),lease.epoch(),lease.cursor(),lease.token()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retry(Lease lease) {
        requireTransaction();
        int delay = Math.min(300, 1 << Math.min(Math.max(lease.attempts(),1),9));
        return jdbc.update("""
                UPDATE recovery_dispatch_outbox SET status='PENDING',lease_token=NULL,lease_until=NULL,
                next_retry_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND)
                WHERE user_id=? AND stream_epoch=? AND mutation_cursor=? AND status='PROCESSING'
                AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)
                """,delay,lease.userId(),lease.epoch(),lease.cursor(),lease.token()) == 1;
    }

    private void requireTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Dispatch leases require a write transaction");
        }
    }
}
