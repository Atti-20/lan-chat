package com.lanchat.mapper;

import com.lanchat.entity.Broadcast;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the mapper annotation against a real SQL engine.  A mock of
 * {@link BroadcastMapper#selectPending(Long)} cannot catch precedence errors
 * such as a stale {@code viewed_at} reviving a submitted confirmation.
 */
class BroadcastMapperIntegrationTest {

    private SqlSessionFactory sqlSessions;

    @BeforeEach
    void setUp() throws Exception {
        String databaseName = "broadcast_mapper_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = new PooledDataSource(
                "org.h2.Driver",
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        createSchema(dataSource);
        seedReceipts(dataSource);

        Configuration configuration = new Configuration(
                new Environment("broadcast-mapper-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BroadcastMapper.class);
        sqlSessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void submittedReceiverIsNotRevivedAsPendingWhenViewedAtIsStillNull() {
        List<Long> pendingIds = withMapper(mapper -> mapper.selectPending(8L).stream()
                .map(Broadcast::getId)
                .toList());

        // ids=2/6 are submitted (EXECUTED / NEED_SUPPORT) and deliberately
        // have no viewed_at to reproduce an older client's persisted state.
        assertEquals(List.of(3L, 1L), pendingIds);
        assertFalse(pendingIds.contains(2L));
        assertFalse(pendingIds.contains(6L));
    }

    @Test
    void completedBroadcastRemainsVisibleToItsRecipient() {
        List<Long> visibleIds = withMapper(mapper -> mapper.selectVisible(8L).stream()
                .map(Broadcast::getId)
                .toList());

        // id=5 became globally COMPLETED after the recipient submitted. It
        // must remain available in the receiver's “已完成” filter.
        assertTrue(visibleIds.contains(5L));
    }

    private <T> T withMapper(MapperCall<T> call) {
        try (SqlSession session = sqlSessions.openSession()) {
            return call.call(session.getMapper(BroadcastMapper.class));
        }
    }

    private void createSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE broadcast (
                        id BIGINT PRIMARY KEY,
                        sender_id BIGINT NOT NULL,
                        title VARCHAR(100) NOT NULL,
                        content VARCHAR(1000) NOT NULL,
                        priority VARCHAR(20) NOT NULL,
                        scope_type VARCHAR(20) NOT NULL,
                        scope_group_id BIGINT,
                        confirmation_required TINYINT NOT NULL,
                        confirmation_options VARCHAR(1000),
                        deadline_at TIMESTAMP,
                        bypass_mute TINYINT,
                        repeat_reminder TINYINT,
                        require_image_proof TINYINT,
                        require_location_proof TINYINT,
                        completed_at TIMESTAMP,
                        status VARCHAR(20) NOT NULL,
                        create_time TIMESTAMP NOT NULL,
                        update_time TIMESTAMP NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE broadcast_receiver (
                        id BIGINT PRIMARY KEY,
                        broadcast_id BIGINT NOT NULL,
                        user_id BIGINT NOT NULL,
                        delivered_at TIMESTAMP,
                        viewed_at TIMESTAMP,
                        confirm_status VARCHAR(32) NOT NULL,
                        confirmed_at TIMESTAMP,
                        target_status VARCHAR(20) NOT NULL,
                        completed_at TIMESTAMP
                    )
                    """);
        }
    }

    private void seedReceipts(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insertBroadcast(statement, 1L, true, "ACTIVE");
            insertBroadcast(statement, 2L, true, "ACTIVE");
            insertBroadcast(statement, 3L, false, "ACTIVE");
            insertBroadcast(statement, 4L, false, "ACTIVE");
            insertBroadcast(statement, 5L, true, "COMPLETED");
            insertBroadcast(statement, 6L, true, "ACTIVE");

            statement.execute("""
                    INSERT INTO broadcast_receiver
                    (id, broadcast_id, user_id, confirm_status, confirmed_at, viewed_at, target_status)
                    VALUES
                    (1, 1, 8, 'PENDING', NULL, NULL, 'ACTIVE'),
                    (2, 2, 8, 'EXECUTED', CURRENT_TIMESTAMP, NULL, 'ACTIVE'),
                    (3, 3, 8, 'NOT_REQUIRED', NULL, NULL, 'ACTIVE'),
                    (4, 4, 8, 'NOT_REQUIRED', NULL, CURRENT_TIMESTAMP, 'ACTIVE'),
                    (5, 5, 8, 'EXECUTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ACTIVE'),
                    (6, 6, 8, 'NEED_SUPPORT', CURRENT_TIMESTAMP, NULL, 'ACTIVE')
                    """);
        }
    }

    private void insertBroadcast(Statement statement, long id, boolean confirmationRequired, String status)
            throws Exception {
        statement.execute("""
                INSERT INTO broadcast
                (id, sender_id, title, content, priority, scope_type, confirmation_required,
                 status, create_time, update_time)
                VALUES (%d, 1, '验收广播', '正文', 'NORMAL', 'USERS', %d, '%s',
                        TIMESTAMP '2026-09-05 12:00:00', TIMESTAMP '2026-09-05 12:00:00')
                """.formatted(id, confirmationRequired ? 1 : 0, status));
    }

    @FunctionalInterface
    private interface MapperCall<T> {
        T call(BroadcastMapper mapper);
    }
}
