package com.lanchat.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fair, bounded maintenance; each user's prefix commits independently. */
@Component
@ConditionalOnProperty(name="meshx.recovery.dual-write-enabled",havingValue="true")
public class MutationRetentionWorker {
    private static final Logger log=LoggerFactory.getLogger(MutationRetentionWorker.class);
    private final JdbcTemplate jdbc;
    private final MutationRetention retention;
    private long afterUser;
    public MutationRetentionWorker(JdbcTemplate jdbc,MutationRetention retention) {
        this.jdbc=jdbc;this.retention=retention;
    }

    @Scheduled(fixedDelayString="${meshx.recovery.retention-delay-ms:60000}")
    public void collect() {
        // A single bounded DELETE holds no stream locks, avoiding the session/stream lock inversion.
        jdbc.update("DELETE FROM recovery_session WHERE expires_at<=UTC_TIMESTAMP(6) LIMIT 1000");
        var users=jdbc.queryForList("""
                SELECT user_id FROM recovery_user_stream WHERE user_id>? AND latest_cursor>floor_cursor
                ORDER BY user_id LIMIT 100
                """,Long.class,afterUser);
        if(users.isEmpty()) {afterUser=0;return;}
        for(long user:users) {
            try {retention.prune(user,2000);}
            catch(RuntimeException failure) {
                log.warn("Recovery prefix collection failed; retained for retry",failure);
            } finally {afterUser=user;}
        }
    }
}
