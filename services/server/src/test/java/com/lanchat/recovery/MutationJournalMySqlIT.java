package com.lanchat.recovery;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.MessageRecallMapper;
import com.lanchat.service.ChatMessageService;
import com.lanchat.service.ConversationService;
import com.lanchat.service.impl.ChatMessageServiceImpl;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit integration target; fails rather than skips if the owned MySQL fixture is absent. */
class MutationJournalMySqlIT {
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static MutationJournal journal;
    static SqlSessionTemplate sqlSession;
    private final com.lanchat.websocket.ChatWebSocketHandler friendNotifications = mock(com.lanchat.websocket.ChatWebSocketHandler.class);
    private final com.lanchat.service.FileService cleanupFiles = mock(com.lanchat.service.FileService.class);
    static final MutationFact RECALLED = MutationFact.message(
            MutationFact.Type.MESSAGE_RECALLED, "group:21", "message-a", 2);

    @BeforeAll
    static void database() throws Exception {
        String url = System.getenv("MESHX_RECOVERY_TEST_URL");
        assertNotNull(url, "Explicit owned MySQL fixture is required");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mx_recovery_test[?].*"));
        dataSource = new DriverManagerDataSource(url, "root", System.getenv("MESHX_RECOVERY_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        assertEquals("mx_recovery_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8."));
        new ResourceDatabasePopulator(new FileSystemResource(System.getenv("MESHX_RECOVERY_TEST_SQL")))
                .execute(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS mutation_test_business(id INT PRIMARY KEY, state INT) ENGINE=InnoDB");
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        journal = new MutationJournal(jdbc);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_message (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, message_id VARCHAR(128) UNIQUE NOT NULL,
                conversation_id VARCHAR(128), sequence BIGINT, from_user_id BIGINT, is_burn INT,
                is_recalled INT, status INT, content TEXT, create_time DATETIME(6),
                client_msg_id VARCHAR(128), sender_device_id BIGINT, to_user_id BIGINT,
                group_id BIGINT, type VARCHAR(24), file_path VARCHAR(255), reply_to_id VARCHAR(128),
                mention_user_ids TEXT, burn_duration INT, client_created_at DATETIME(6)) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS message_recall (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, message_id VARCHAR(128) UNIQUE,
                operator_id BIGINT, recall_time DATETIME(6)) ENGINE=InnoDB
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS conversation (id VARCHAR(128) PRIMARY KEY, type VARCHAR(20),source_id BIGINT,last_sequence BIGINT DEFAULT 0,status VARCHAR(16),last_message_id VARCHAR(128),create_time DATETIME,update_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS group_member (id BIGINT AUTO_INCREMENT PRIMARY KEY,group_id BIGINT,user_id BIGINT,role INT,mute_until DATETIME,join_time DATETIME,UNIQUE(group_id,user_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS friendship (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,friend_id BIGINT,is_blocked INT,remark VARCHAR(80),group_name VARCHAR(80),is_muted INT,is_pinned INT,create_time DATETIME,UNIQUE(user_id,friend_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS friend_request (id BIGINT AUTO_INCREMENT PRIMARY KEY,from_user_id BIGINT,to_user_id BIGINT,message VARCHAR(80),status INT,create_time DATETIME,handle_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS temporary_room (id BIGINT AUTO_INCREMENT PRIMARY KEY,room_name VARCHAR(80),purpose VARCHAR(500),owner_id BIGINT,room_code VARCHAR(12) UNIQUE,expires_at DATETIME,max_members INT,allow_guests INT,allow_member_invite INT,allow_file_upload INT,allow_file_download INT,allow_forward INT,message_retention_days INT,allow_external_sync INT,expire_action VARCHAR(16),status VARCHAR(16),create_time DATETIME,update_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS file_metadata (id BIGINT PRIMARY KEY,file_hash VARCHAR(64),file_name VARCHAR(255),file_path VARCHAR(80),file_size BIGINT,file_type VARCHAR(80),file_suffix VARCHAR(10),storage_type VARCHAR(16),upload_user_id BIGINT,create_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS file_access_grant (id BIGINT AUTO_INCREMENT PRIMARY KEY,file_id BIGINT,user_id BIGINT,grant_type VARCHAR(24),create_time DATETIME,UNIQUE(file_id,user_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS broadcast_evidence (file_id BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS file_transfer (file_metadata_id BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS file_upload_session (completed_file_id BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS `user` (id BIGINT PRIMARY KEY,avatar VARCHAR(255)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mention_read_receipt (message_id VARCHAR(128),user_id BIGINT,PRIMARY KEY(message_id,user_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_group (id BIGINT AUTO_INCREMENT PRIMARY KEY,group_name VARCHAR(40),avatar VARCHAR(255),announcement TEXT,owner_id BIGINT,max_members INT,join_mode INT,create_time DATETIME,update_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS conversation_member (id BIGINT AUTO_INCREMENT PRIMARY KEY,conversation_id VARCHAR(128),user_id BIGINT,role VARCHAR(24),last_read_sequence BIGINT,receipt_start_sequence BIGINT,unread_count INT,is_muted INT,is_pinned INT,join_time DATETIME,left_time DATETIME,UNIQUE(conversation_id,user_id)) ENGINE=InnoDB");
        var config = new MybatisConfiguration();
        config.addMapper(ChatMessageMapper.class);
        config.addMapper(MessageRecallMapper.class);
        config.addMapper(com.lanchat.mapper.ConversationMapper.class);
        config.addMapper(com.lanchat.mapper.GroupMemberMapper.class);
        config.addMapper(com.lanchat.mapper.ChatGroupMapper.class);
        config.addMapper(com.lanchat.mapper.ConversationMemberMapper.class);
        config.addMapper(com.lanchat.mapper.FriendshipMapper.class);
        config.addMapper(com.lanchat.mapper.FriendRequestMapper.class);
        config.addMapper(com.lanchat.mapper.TemporaryRoomMapper.class);
        config.addMapper(com.lanchat.mapper.FileMetadataMapper.class);
        config.addMapper(com.lanchat.mapper.FileAccessGrantMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(config);
        sqlSession = new SqlSessionTemplate(factory.getObject());
    }

    @BeforeEach
    void cleanOwnedFixture() {
        jdbc.update("DELETE FROM recovery_session");
        jdbc.update("DELETE FROM recovery_access_state");
        jdbc.update("DELETE FROM recovery_message_state");
        jdbc.update("DELETE FROM recovery_dispatch_outbox");
        jdbc.update("DELETE FROM recovery_mutation");
        jdbc.update("DELETE FROM recovery_user_stream");
        jdbc.update("DELETE FROM mutation_test_business");
        jdbc.update("DELETE FROM message_recall");
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM conversation");
        jdbc.update("DELETE FROM group_member");
        jdbc.update("DELETE FROM friendship");
        jdbc.update("DELETE FROM friend_request");
        jdbc.update("DELETE FROM temporary_room");
        for (String table : List.of("file_metadata","file_access_grant","broadcast_evidence","file_transfer","file_upload_session","`user`")) jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM mention_read_receipt");
        jdbc.update("DELETE FROM chat_group");
        jdbc.update("DELETE FROM conversation_member");
    }

    @Test
    void requiresActualWriteTransaction() {
        assertThrows(IllegalStateException.class, () -> journal.append(UUID.randomUUID(), List.of(7L), RECALLED));
        var readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readOnly.execute(status ->
                journal.append(UUID.randomUUID(), List.of(7L), RECALLED)));
        assertEquals(0, count("recovery_user_stream"));
    }

    @Test
    void businessCounterLogAndDispatchRollBackTogetherWithoutGap() {
        UUID source = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            jdbc.update("INSERT INTO mutation_test_business VALUES (1,2)");
            journal.append(source, List.of(9L, 7L), RECALLED);
            throw new IllegalStateException("synthetic business failure");
        }));
        for (String table : List.of("mutation_test_business", "recovery_user_stream",
                "recovery_mutation", "recovery_dispatch_outbox")) assertEquals(0, count(table));
        var result = tx.execute(status -> journal.append(source, List.of(9L, 7L, 7L), RECALLED));
        assertEquals(List.of(7L, 9L), result.stream().map(MutationJournal.Appended::userId).toList());
        assertEquals(List.of("1", "1"), result.stream().map(MutationJournal.Appended::cursor).toList());
        assertNotEquals(result.get(0).eventId(), result.get(1).eventId());
        assertEquals(2, count("recovery_dispatch_outbox"));
    }

    @Test
    void retryIsStableAndConflictingRecipientRollsBackEntireFanout() {
        UUID source = UUID.randomUUID();
        var first = tx.execute(status -> journal.append(source, List.of(9L), RECALLED));
        assertEquals(first, tx.execute(status -> journal.append(source, List.of(9L), RECALLED)));
        MutationFact burned = MutationFact.message(MutationFact.Type.MESSAGE_BURNED, "group:21", "message-a", 2);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                journal.append(source, List.of(7L, 9L), burned)));
        assertEquals(1, count("recovery_user_stream"));
        assertEquals(1, count("recovery_mutation"));
        assertEquals(1, count("recovery_dispatch_outbox"));
    }

    @Test
    void twoServiceInstancesCommitContinuousPositionsForConcurrentWriters() throws Exception {
        MutationJournal other = new MutationJournal(new JdbcTemplate(dataSource));
        var executor = Executors.newFixedThreadPool(6);
        try {
            var calls = new ArrayList<Callable<List<MutationJournal.Appended>>>();
            for (int i = 0; i < 24; i++) {
                MutationJournal target = i % 2 == 0 ? journal : other;
                UUID source = UUID.randomUUID();
                calls.add(() -> tx.execute(status -> target.append(source, List.of(9L, 7L), RECALLED)));
            }
            for (var future : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
                assertFalse(future.isCancelled(), "Concurrent journal timeout");
                assertEquals(2, future.get().size());
            }
            for (long user : List.of(7L, 9L)) {
                var positions = jdbc.queryForList("SELECT mutation_cursor FROM recovery_mutation WHERE user_id=? ORDER BY mutation_cursor",
                        Long.class, user);
                assertEquals(24, positions.size());
                for (int i = 0; i < 24; i++) assertEquals(i + 1L, positions.get(i));
            }
            assertEquals(48, count("recovery_dispatch_outbox"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void retryAfterEarlierConsistentReadSeesNewlyCommittedEvent() throws Exception {
        UUID source = UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.execute(status -> {
                assertEquals(0, count("recovery_mutation")); // establishes an older MVCC view
                var first = assertDoesNotThrow(() -> executor.submit(() ->
                        tx.execute(other -> journal.append(source, List.of(7L), RECALLED)))
                        .get(10, TimeUnit.SECONDS));
                assertEquals(first, journal.append(source, List.of(7L), RECALLED));
                return null;
            });
            assertEquals(1, count("recovery_mutation"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void preservesDecimalPrecisionAndRejectsOverflowWithoutPartialFanout() {
        tx.execute(status -> journal.append(UUID.randomUUID(), List.of(7L, 9L), RECALLED));
        jdbc.update("UPDATE recovery_user_stream SET latest_cursor=? WHERE user_id=7", 9007199254740991L);
        var next = tx.execute(status -> journal.append(UUID.randomUUID(), List.of(7L), RECALLED));
        assertEquals("9007199254740992", next.get(0).cursor());
        jdbc.update("UPDATE recovery_user_stream SET latest_cursor=? WHERE user_id=9", Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                journal.append(UUID.randomUUID(), List.of(7L, 9L), RECALLED)));
        assertEquals(9007199254740992L, jdbc.queryForObject(
                "SELECT latest_cursor FROM recovery_user_stream WHERE user_id=7", Long.class));
        assertEquals(3, count("recovery_mutation"));
        assertEquals(3, count("recovery_dispatch_outbox"));
    }

    @Test
    void accessReasonsRoundTripWithoutMessageBodyFields() {
        var changed = new MutationFact(MutationFact.Type.CONVERSATION_ACCESS_CHANGED, "private:7:9", null,
                null, 2L, true, false, false, MutationFact.Reason.FRIEND_DELETED);
        var revoked = new MutationFact(MutationFact.Type.CONVERSATION_ACCESS_REVOKED, "group:21", null,
                null, 3L, false, false, null, MutationFact.Reason.REMOVED);
        for (MutationFact fact : List.of(changed, revoked)) {
            UUID source = UUID.randomUUID();
            var result = tx.execute(status -> journal.append(source, List.of(7L), fact));
            assertEquals(result, tx.execute(status -> journal.append(source, List.of(7L), fact)));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE message_id IS NOT NULL", Integer.class));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    ChatMessageService terminalService(boolean hasAccess) {
        return terminalService(hasAccess, true);
    }

    private ChatMessageService terminalService(boolean hasAccess, boolean dualWrite) {
        var target = new ChatMessageServiceImpl();
        ReflectionTestUtils.setField(target, "baseMapper", sqlSession.getMapper(ChatMessageMapper.class));
        ReflectionTestUtils.setField(target, "messageRecallMapper", sqlSession.getMapper(MessageRecallMapper.class));
        ConversationService permissions = mock(ConversationService.class);
        ConversationWriteGuard guard = new ConversationWriteGuard(jdbc);
        when(permissions.canAccessForWrite(anyString(), anyLong())).thenAnswer(call ->
                hasAccess && guard.permission(call.getArgument(0), call.getArgument(1)).readAllowed());
        when(permissions.getReadableRecipientsForWrite(anyString())).thenAnswer(call -> guard.recipients(call.getArgument(0)));
        doAnswer(call -> { guard.lock(call.getArgument(0)); return null; }).when(permissions).lockConversationForWrite(anyString());
        ReflectionTestUtils.setField(target, "messageMutationRecorder", new MessageMutationRecorder(jdbc, journal, dualWrite));
        ReflectionTestUtils.setField(target, "conversationService", permissions);
        ReflectionTestUtils.setField(target, "attachmentCleanup",attachmentCleanup());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (ChatMessageService) proxy.getProxy();
    }

    private void seedTerminalMessage() {
        jdbc.update("INSERT INTO chat_group(id,owner_id,max_members) VALUES (21,9,200)");
        jdbc.update("INSERT INTO conversation(id,type,status,last_sequence) VALUES ('group:21','GROUP','ACTIVE',62),('private:7:9','PRIVATE','ACTIVE',0)");
        jdbc.update("INSERT INTO group_member(group_id,user_id,role) VALUES (21,7,0),(21,9,2)");
        jdbc.update("""
                INSERT INTO chat_message(message_id,conversation_id,sequence,from_user_id,is_burn,is_recalled,status,content,create_time)
                VALUES ('terminal','group:21',62,7,1,0,0,'synthetic',?)
                """, java.time.LocalDateTime.now());
    }

    private void seedFiveHundredRecipients() {
        seedTerminalMessage();
        jdbc.update("UPDATE chat_group SET max_members=500 WHERE id=21");
        var members = new ArrayList<Object[]>();
        for (long id = 1000; id < 1498; id++) members.add(new Object[]{id});
        jdbc.batchUpdate("INSERT INTO group_member(group_id,user_id,role) VALUES (21,?,0)", members);
        assertEquals(500, count("group_member"));
    }

    @Test
    void fiveHundredRecipientsReceiveOneActualRecallAndRetryDoesNotDuplicate() {
        seedFiveHundredRecipients();
        var service = terminalService(true);
        assertTrue(service.recallMessage("terminal", 7L));
        assertTrue(service.recallMessage("terminal", 7L));
        assertEquals("", jdbc.queryForObject("SELECT content FROM chat_message WHERE message_id='terminal'", String.class));
        assertEquals(500, count("recovery_mutation"));
        assertEquals(500, count("recovery_dispatch_outbox"));
        assertEquals(500, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_user_stream WHERE latest_cursor=1", Integer.class));
        assertEquals(500, jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM recovery_mutation WHERE message_id='terminal' AND mutation_type='MESSAGE_RECALLED'", Integer.class));
        assertEquals(1, count("message_recall"));
    }

    @Test
    void fiveHundredthRecipientFailureRollsBackBodyAndAllEarlierRecipients() {
        seedFiveHundredRecipients();
        jdbc.update("INSERT INTO recovery_user_stream(user_id,stream_epoch,latest_cursor) VALUES (1497,?,?)",
                UUID.randomUUID().toString(), Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> terminalService(true).recallMessage("terminal", 7L));
        assertEquals("synthetic", jdbc.queryForObject("SELECT content FROM chat_message WHERE message_id='terminal'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT is_recalled FROM chat_message WHERE message_id='terminal'", Integer.class));
        for (String table : List.of("message_recall", "recovery_message_state", "recovery_mutation", "recovery_dispatch_outbox")) {
            assertEquals(0, count(table), table);
        }
        assertEquals(List.of(1497L), jdbc.queryForList("SELECT user_id FROM recovery_user_stream", Long.class));
    }

    @Test
    void twoIndependentJvmWritersSerializeFiveHundredStreamsAndSharedRetries() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("meshx-journal-processes-");
        var workers = new ArrayList<Process>();
        try {
            String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String shared = UUID.randomUUID().toString();
            for (String name : List.of("first", "second")) {
                workers.add(new ProcessBuilder(javaExecutable, "-cp", classpath, MutationJournalProcessProbe.class.getName(),
                        directory.toString(), name, shared).redirectErrorStream(true)
                        .redirectOutput(directory.resolve(name + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!(java.nio.file.Files.exists(directory.resolve("first.ready")) &&
                    java.nio.file.Files.exists(directory.resolve("second.ready")))) {
                assertTrue(workers.stream().allMatch(Process::isAlive), "Worker stopped before start barrier");
                assertTrue(System.nanoTime() < deadline, "Worker startup timed out");
                Thread.sleep(20);
            }
            java.nio.file.Files.writeString(directory.resolve("go"), "start");
            for (Process worker : workers) {
                assertTrue(worker.waitFor(120, TimeUnit.SECONDS), "Independent JVM timed out");
                assertEquals(0, worker.exitValue());
            }
            assertEquals(500, count("recovery_user_stream"));
            assertEquals(3500, count("recovery_mutation"));
            assertEquals(3500, count("recovery_dispatch_outbox"));
            assertEquals(500, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_user_stream WHERE latest_cursor=7", Integer.class));
            assertEquals(500, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT user_id FROM recovery_mutation GROUP BY user_id HAVING COUNT(*)=7 AND MIN(mutation_cursor)=1 AND MAX(mutation_cursor)=7) continuous_streams", Integer.class));
            assertEquals(500, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE message_id='shared-message'", Integer.class));
        } finally {
            for (Process worker : workers) {
                if (worker.isAlive()) worker.destroyForcibly();
                worker.waitFor(10, TimeUnit.SECONDS);
            }
            try (var files = java.nio.file.Files.list(directory)) {
                for (var file : files.toList()) {
                    if (file.toString().endsWith(".log")) System.out.println(java.nio.file.Files.readString(file));
                    java.nio.file.Files.deleteIfExists(file);
                }
            }
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    @Test
    void actualRecallAndBurnMethodsCommitOnlyOneTerminalState() throws Exception {
        seedTerminalMessage();
        ChatMessageService first = terminalService(true), second = terminalService(true);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var recall = executor.submit(() -> {
                start.await();
                try { first.recallMessage("terminal", 7L); return "recalled"; }
                catch (IllegalArgumentException rejected) { return "rejected"; }
            });
            var burn = executor.submit(() -> {
                start.await();
                try { second.markAsBurned("terminal", 7L); return "burned"; }
                catch (IllegalArgumentException rejected) { return "rejected"; }
            });
            start.countDown();
            var outcomes = List.of(recall.get(10, TimeUnit.SECONDS), burn.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("rejected"::equals).count());
            var row = jdbc.queryForMap("SELECT is_recalled,status,content FROM chat_message WHERE message_id='terminal'");
            assertEquals("", row.get("content"));
            assertEquals(2, count("recovery_mutation"));
            assertEquals(2, count("recovery_dispatch_outbox"));
            assertEquals(2L, jdbc.queryForObject("SELECT object_version FROM recovery_message_state WHERE message_id='terminal'", Long.class));
            if (outcomes.contains("recalled")) {
                assertEquals(1, row.get("is_recalled"));
                assertEquals(0, row.get("status"));
                assertEquals(1, count("message_recall"));
            } else {
                assertEquals(0, row.get("is_recalled"));
                assertEquals(2, row.get("status"));
                assertEquals(0, count("message_recall"));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void recallLogFailureRollsBackActualMessageMutation() {
        seedTerminalMessage();
        jdbc.update("INSERT INTO message_recall(message_id,operator_id) VALUES ('terminal',7)");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> terminalService(true).recallMessage("terminal", 7L));
        assertEquals("synthetic", jdbc.queryForObject("SELECT content FROM chat_message WHERE message_id='terminal'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT is_recalled FROM chat_message WHERE message_id='terminal'", Integer.class));
    }

    @Test
    void successfulRecallRetryStillRejectsRevokedAccessOrDifferentOperator() {
        seedTerminalMessage();
        assertTrue(terminalService(true).recallMessage("terminal", 7L));
        assertThrows(IllegalArgumentException.class, () -> terminalService(false).recallMessage("terminal", 7L));
        assertThrows(IllegalArgumentException.class, () -> terminalService(true).recallMessage("terminal", 8L));
        assertTrue(terminalService(true).recallMessage("terminal", 7L));
        assertEquals(1, count("message_recall"));
        assertEquals(2, count("recovery_mutation"));
    }

    @Test
    void laterRecipientFailureRollsBackBodyStateLogAndEarlierRecipient() {
        seedTerminalMessage();
        jdbc.update("INSERT INTO recovery_user_stream(user_id,stream_epoch,latest_cursor) VALUES (9,?,?)",
                UUID.randomUUID().toString(), Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> terminalService(true).recallMessage("terminal", 7L));
        assertEquals("synthetic", jdbc.queryForObject("SELECT content FROM chat_message WHERE message_id='terminal'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT is_recalled FROM chat_message WHERE message_id='terminal'", Integer.class));
        for (String table : List.of("message_recall", "recovery_message_state", "recovery_mutation", "recovery_dispatch_outbox")) {
            assertEquals(0, count(table), table);
        }
        assertEquals(List.of(9L), jdbc.queryForList("SELECT user_id FROM recovery_user_stream", Long.class));
    }

    @Test
    void privateTerminalFactReachesBothHistoricalParticipantsWithoutFriendshipLookup() {
        seedTerminalMessage();
        jdbc.update("UPDATE chat_message SET conversation_id='private:7:9'");
        terminalService(true).markAsBurned("terminal", 7L);
        assertEquals(List.of(7L, 9L), jdbc.queryForList("SELECT user_id FROM recovery_mutation ORDER BY user_id", Long.class));
        assertEquals("BURNED", jdbc.queryForObject("SELECT state FROM recovery_message_state", String.class));
    }

    @Test
    void additiveDisabledStageKeepsLegacyOperationWithoutAdvertisingSafety() {
        seedTerminalMessage();
        terminalService(true, false).recallMessage("terminal", 7L);
        assertEquals(1, count("message_recall"));
        assertEquals(0, count("recovery_message_state"));
        assertEquals(0, count("recovery_mutation"));
    }

    @Test
    void technicalBroadcastCardRedactionUsesTheSameDurableTerminalPath() {
        seedTerminalMessage();
        jdbc.update("""
                UPDATE chat_message SET conversation_id='private:7:9',to_user_id=9,type='broadcast',
                content='{"broadcastId":12,"kind":"BROADCAST_OVERVIEW"}'
                """);
        var service = terminalService(true);
        assertEquals(1, service.redactSystemBroadcastCards(7L, 9L, 12L).size());
        assertTrue(service.redactSystemBroadcastCards(7L, 9L, 12L).isEmpty());
        assertEquals(2, count("recovery_mutation"));
        assertEquals(2, count("recovery_dispatch_outbox"));
        assertEquals("RECALLED", jdbc.queryForObject("SELECT state FROM recovery_message_state", String.class));
        assertEquals("", jdbc.queryForObject("SELECT content FROM chat_message", String.class));
    }

    @Test
    void physicalRemovalCanRetainHigherVersionBodyFreeTombstoneInSameTransaction() {
        seedTerminalMessage();
        terminalService(true).recallMessage("terminal", 7L);
        tx.execute(status -> {
            var previous = sqlSession.getMapper(ChatMessageMapper.class).selectByMessageIdForUpdate("terminal");
            jdbc.update("DELETE FROM chat_message WHERE message_id='terminal'");
            new MessageMutationRecorder(jdbc, journal, true)
                    .record(previous, MutationFact.Type.MESSAGE_UNAVAILABLE, List.of(7L, 9L));
            return null;
        });
        assertEquals(0, count("chat_message"));
        assertEquals("UNAVAILABLE", jdbc.queryForObject("SELECT state FROM recovery_message_state", String.class));
        assertEquals(3L, jdbc.queryForObject("SELECT object_version FROM recovery_message_state", Long.class));
        assertEquals(62L, jdbc.queryForObject("SELECT message_sequence FROM recovery_message_state", Long.class));
        assertEquals(4, count("recovery_mutation"));
        assertEquals(4, count("recovery_dispatch_outbox"));
    }

    @Test
    void memberRemovalCommitsAccessVersionAndRetryDoesNotAppend() {
        seedTerminalMessage();
        var service = groupRemovalService(realGroupConversationService());
        assertTrue(service.removeMember(21L, 9L, 7L));
        assertFalse(service.removeMember(21L, 9L, 7L));
        assertEquals(2L, jdbc.queryForObject("SELECT access_version FROM recovery_access_state WHERE user_id=7", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT read_allowed+send_allowed FROM recovery_access_state WHERE user_id=7", Integer.class));
        assertEquals("CONVERSATION_ACCESS_REVOKED", jdbc.queryForObject("SELECT mutation_type FROM recovery_mutation", String.class));
        assertEquals("REMOVED", jdbc.queryForObject("SELECT reason FROM recovery_mutation", String.class));
        assertNull(jdbc.queryForObject("SELECT rebuild_conversation FROM recovery_mutation", Boolean.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT latest_cursor FROM recovery_user_stream WHERE user_id=7", Long.class));
    }

    @Test
    void revocationJournalFailureRollsBackActualMemberDeletionAndAccessVersion() {
        seedTerminalMessage();
        jdbc.update("INSERT INTO recovery_user_stream VALUES (?,?,?,0)", 7L, UUID.randomUUID().toString(), Long.MAX_VALUE);
        var service = groupRemovalService(realGroupConversationService());
        assertThrows(RuntimeException.class, () -> service.removeMember(21L, 9L, 7L));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE user_id=7", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_access_state", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox", Long.class));
    }

    @Test
    void leaveGroupUsesSameDurableRevocationAndOwnerCannotLeave() {
        seedTerminalMessage();
        var service = groupRemovalService(realGroupConversationService());
        assertThrows(IllegalArgumentException.class, () -> service.leaveGroup(21L, 9L));
        assertTrue(service.leaveGroup(21L, 7L));
        assertFalse(service.leaveGroup(21L, 7L));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE user_id=7", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE user_id=9", Long.class));
    }

    @Test
    void removeRejoinRemoveHasIncreasingVersionsAndRebuildGrant() {
        seedTerminalMessage();
        var conversations = realGroupConversationService();
        tx.executeWithoutResult(status -> conversations.ensureGroupConversation(21L));
        var service = groupRemovalService(conversations);
        assertTrue(service.removeMember(21L, 9L, 7L));
        assertNotNull(jdbc.queryForObject("SELECT left_time FROM conversation_member WHERE user_id=7", java.sql.Timestamp.class));
        assertTrue(service.addMembers(21L, 9L, List.of(7L, 7L)));
        assertNull(jdbc.queryForObject("SELECT left_time FROM conversation_member WHERE user_id=7", java.sql.Timestamp.class));
        assertEquals(Boolean.TRUE, tx.execute(status -> new ConversationWriteGuard(jdbc).permission("group:21", 7L).sendAllowed()));
        assertTrue(service.addMembers(21L, 9L, List.of(7L)));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation", Long.class));
        assertEquals("GRANTED", jdbc.queryForObject("SELECT reason FROM recovery_mutation WHERE mutation_cursor=2", String.class));
        assertEquals(true, jdbc.queryForObject("SELECT rebuild_conversation FROM recovery_mutation WHERE mutation_cursor=2", Boolean.class));
        assertEquals(3L, jdbc.queryForObject("SELECT access_version FROM recovery_access_state WHERE user_id=7", Long.class));
        assertTrue(service.removeMember(21L, 9L, 7L));
        assertEquals(List.of(2L,3L,4L), jdbc.queryForList("SELECT access_version FROM recovery_mutation ORDER BY mutation_cursor", Long.class));
    }

    @Test
    void laterGrantFailureRollsBackAllAddedMembersAndEarlierRecipientStream() {
        seedTerminalMessage();
        jdbc.update("INSERT INTO recovery_user_stream VALUES (?,?,?,0)", 13L, UUID.randomUUID().toString(), Long.MAX_VALUE);
        var service = groupRemovalService(realGroupConversationService());
        assertThrows(RuntimeException.class, () -> service.addMembers(21L, 9L, List.of(13L, 11L)));
        assertEquals(List.of(7L,9L), jdbc.queryForList("SELECT user_id FROM group_member ORDER BY user_id", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_access_state", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_user_stream WHERE user_id=11", Long.class));
    }

    @Test
    void createGroupWritesDirectoryGrantForOwnerAndEveryDistinctMember() {
        var service = groupRemovalService(realGroupConversationService());
        var dto = new com.lanchat.dto.GroupCreateDTO();
        dto.setGroupName("恢复验证群");
        dto.setMemberIds(List.of(13L,7L,13L,9L));
        var group = service.createGroup(9L, dto);
        String cid = "group:" + group.getId();
        assertEquals(List.of(7L,9L,13L), jdbc.queryForList("SELECT user_id FROM recovery_mutation ORDER BY user_id", Long.class));
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE conversation_id=? AND reason='GRANTED' AND rebuild_conversation=TRUE AND read_allowed=TRUE AND send_allowed=TRUE", Long.class, cid));
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member WHERE conversation_id=? AND left_time IS NULL", Long.class, cid));
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox", Long.class));
    }

    @Test
    void dissolveGroupAtomicallyRetainsTombstoneAndRevokesAllMembers() {
        seedTerminalMessage();
        jdbc.update("UPDATE chat_message SET group_id=21 WHERE message_id='terminal'");
        jdbc.update("INSERT INTO mention_read_receipt VALUES ('terminal',9)");
        var conversations = realGroupConversationService();
        tx.executeWithoutResult(status -> conversations.ensureGroupConversation(21L));
        var service = groupRemovalService(conversations);
        assertThrows(IllegalArgumentException.class, () -> service.dissolveGroup(21L, 7L));
        assertTrue(service.dissolveGroup(21L, 9L));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM mention_read_receipt", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM group_member", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM chat_group", Long.class));
        assertEquals("DESTROYED", jdbc.queryForObject("SELECT status FROM conversation WHERE id='group:21'", String.class));
        assertEquals("UNAVAILABLE", jdbc.queryForObject("SELECT state FROM recovery_message_state", String.class));
        assertEquals(62L, jdbc.queryForObject("SELECT message_sequence FROM recovery_message_state", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT object_version FROM recovery_message_state", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE reason='GROUP_REMOVED'", Long.class));
        assertEquals(4L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member WHERE left_time IS NULL", Long.class));
        assertThrows(IllegalArgumentException.class, () -> service.dissolveGroup(21L, 9L));
        assertEquals(4L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation", Long.class));
    }

    @Test
    void dissolveRevocationFailureRollsBackPhysicalDeletesAndEarlierTombstoneFacts() {
        seedTerminalMessage();
        jdbc.update("UPDATE chat_message SET group_id=21 WHERE message_id='terminal'");
        jdbc.update("INSERT INTO mention_read_receipt VALUES ('terminal',9)");
        // Two remaining positions admit both message facts and user 7 revoke,
        // while user 9 revoke fails after physical deletion was executed.
        jdbc.update("INSERT INTO recovery_user_stream VALUES (?,?,?,0)", 9L, UUID.randomUUID().toString(), Long.MAX_VALUE - 1);
        var conversations = realGroupConversationService();
        tx.executeWithoutResult(status -> conversations.ensureGroupConversation(21L));
        var service = groupRemovalService(conversations);
        assertThrows(RuntimeException.class, () -> service.dissolveGroup(21L, 9L));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM mention_read_receipt", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM chat_group", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM group_member", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member WHERE left_time IS NULL", Long.class));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM conversation WHERE id='group:21'", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_message_state", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_access_state", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox", Long.class));
    }

    private com.lanchat.service.FriendService realFriendService() {
        var service = new com.lanchat.service.impl.FriendServiceImpl();
        ReflectionTestUtils.setField(service, "friendshipMapper", sqlSession.getMapper(com.lanchat.mapper.FriendshipMapper.class));
        ReflectionTestUtils.setField(service, "friendRequestMapper", sqlSession.getMapper(com.lanchat.mapper.FriendRequestMapper.class));
        var users = mock(com.lanchat.mapper.UserMapper.class);
        var acceptor = new com.lanchat.entity.User(); acceptor.setId(9L); acceptor.setNickname("测试接收方");
        when(users.selectById(9L)).thenReturn(acceptor);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "webSocketHandler", friendNotifications);
        ReflectionTestUtils.setField(service, "conversationService", realGroupConversationService());
        ReflectionTestUtils.setField(service, "accessMutationRecorder", new AccessMutationRecorder(jdbc,journal,new ConversationWriteGuard(jdbc),true));
        return (com.lanchat.service.FriendService) transactionalProxy(service);
    }

    @Test
    void deleteFriendRetainsHistoryAndReacceptRequiresHigherVersionRebuild() {
        seedTerminalMessage();
        jdbc.update("UPDATE chat_message SET conversation_id='private:7:9',to_user_id=9");
        jdbc.update("INSERT INTO friendship(user_id,friend_id,is_blocked) VALUES (7,9,0),(9,7,0)");
        var service = realFriendService();
        assertTrue(service.deleteFriend(7L,9L));
        assertTrue(service.deleteFriend(7L,9L));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE reason='FRIEND_DELETED' AND read_allowed=TRUE AND send_allowed=FALSE AND rebuild_conversation=FALSE",Long.class));
        assertEquals("synthetic",jdbc.queryForObject("SELECT content FROM chat_message",String.class));
        jdbc.update("INSERT INTO friend_request(id,from_user_id,to_user_id,status,create_time) VALUES (1,7,9,0,NOW())");
        assertTrue(service.handleFriendRequest(1L,9L,true));
        verify(friendNotifications).sendFriendNotification(eq(7L),anyString());
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE reason='GRANTED' AND access_version=3 AND rebuild_conversation=TRUE",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM friendship",Long.class));
    }

    @Test
    void blockAndUnblockPublishBothSendPermissionsWithoutRevokingHistory() {
        seedTerminalMessage();
        jdbc.update("INSERT INTO friendship(user_id,friend_id,is_blocked) VALUES (7,9,0),(9,7,0)");
        var service = realFriendService();
        assertTrue(service.toggleBlock(7L,9L));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE reason='SEND_DENIED' AND read_allowed=TRUE AND send_allowed=FALSE",Long.class));
        assertTrue(service.toggleBlock(7L,9L));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE reason='UPDATED' AND access_version=3 AND read_allowed=TRUE AND send_allowed=TRUE AND rebuild_conversation=FALSE",Long.class));
        assertEquals(4L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox",Long.class));
    }

    @Test
    void friendDeletionRollsBackBothRelationsWhenSecondRecipientJournalFails() {
        seedTerminalMessage();
        jdbc.update("INSERT INTO friendship(user_id,friend_id,is_blocked) VALUES (7,9,0),(9,7,0)");
        jdbc.update("INSERT INTO recovery_user_stream VALUES (?,?,?,0)",9L,UUID.randomUUID().toString(),Long.MAX_VALUE);
        assertThrows(RuntimeException.class,()->realFriendService().deleteFriend(7L,9L));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM friendship",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_access_state",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox",Long.class));
    }

    @Test
    void acceptNotificationWaitsForCommitAndOuterRollbackCancelsIt() {
        jdbc.update("INSERT INTO friend_request(id,from_user_id,to_user_id,status,create_time) VALUES (1,7,9,0,NOW())");
        var service = realFriendService();
        tx.executeWithoutResult(status -> {
            assertTrue(service.handleFriendRequest(1L,9L,true));
            verifyNoInteractions(friendNotifications);
            status.setRollbackOnly();
        });
        verifyNoInteractions(friendNotifications);
        assertEquals(0,jdbc.queryForObject("SELECT status FROM friend_request WHERE id=1",Integer.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM friendship",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM conversation",Long.class));
    }

    @Test
    void muteExtendAndExplicitUnmutePreserveHistoryAndOnlyLogPermissionChanges() {
        seedTerminalMessage();
        var service = groupRemovalService(realGroupConversationService());
        assertTrue(service.muteMember(21L,9L,7L,10));
        assertTrue(service.muteMember(21L,9L,7L,20));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
        assertEquals("SEND_DENIED",jdbc.queryForObject("SELECT reason FROM recovery_mutation",String.class));
        assertTrue(service.muteMember(21L,9L,7L,0));
        assertNull(jdbc.queryForObject("SELECT mute_until FROM group_member WHERE user_id=7",java.sql.Timestamp.class));
        assertEquals(3L,jdbc.queryForObject("SELECT access_version FROM recovery_access_state",Long.class));
        assertEquals("UPDATED",jdbc.queryForObject("SELECT reason FROM recovery_mutation WHERE mutation_cursor=2",String.class));
        assertEquals(true,jdbc.queryForObject("SELECT send_allowed FROM recovery_access_state",Boolean.class));
        assertEquals("synthetic",jdbc.queryForObject("SELECT content FROM chat_message",String.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE read_allowed=TRUE AND rebuild_conversation=FALSE",Long.class));
    }

    @Test
    void muteJournalFailureRollsBackDeadlineAndUnauthorizedMuteDoesNotWrite() {
        seedTerminalMessage();
        var service = groupRemovalService(realGroupConversationService());
        assertThrows(IllegalArgumentException.class,()->service.muteMember(21L,7L,9L,10));
        jdbc.update("INSERT INTO recovery_user_stream VALUES (?,?,?,0)",7L,UUID.randomUUID().toString(),Long.MAX_VALUE);
        assertThrows(RuntimeException.class,()->service.muteMember(21L,9L,7L,10));
        assertNull(jdbc.queryForObject("SELECT mute_until FROM group_member WHERE user_id=7",java.sql.Timestamp.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_access_state",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
    }

    private GroupMuteExpiryWorker expiryWorker() {
        return new GroupMuteExpiryWorker(jdbc,
                new AccessMutationRecorder(jdbc,journal,new ConversationWriteGuard(jdbc),true),
                new DataSourceTransactionManager(dataSource));
    }

    @Test
    void expiredMuteWorkerAdvancesOnceAndNeverChangesDeadlineOrHistory() {
        seedTerminalMessage();
        var service = groupRemovalService(realGroupConversationService());
        service.muteMember(21L,9L,7L,10);
        jdbc.update("UPDATE group_member SET mute_until=? WHERE user_id=7",java.time.LocalDateTime.now().minusMinutes(1));
        var deadline = jdbc.queryForObject("SELECT mute_until FROM group_member WHERE user_id=7",java.sql.Timestamp.class);
        expiryWorker().reconcile();
        expiryWorker().reconcile();
        assertEquals(3L,jdbc.queryForObject("SELECT access_version FROM recovery_access_state",Long.class));
        assertEquals(true,jdbc.queryForObject("SELECT send_allowed FROM recovery_access_state",Boolean.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
        assertEquals("UPDATED",jdbc.queryForObject("SELECT reason FROM recovery_mutation WHERE mutation_cursor=2",String.class));
        assertEquals(deadline,jdbc.queryForObject("SELECT mute_until FROM group_member WHERE user_id=7",java.sql.Timestamp.class));
        assertEquals("synthetic",jdbc.queryForObject("SELECT content FROM chat_message",String.class));
    }

    @Test
    void remuteAfterDeadlineCommitsExpiryThenNewDenialWithoutStaleWorkerOverride() {
        seedTerminalMessage();
        var service = groupRemovalService(realGroupConversationService());
        service.muteMember(21L,9L,7L,10);
        jdbc.update("UPDATE group_member SET mute_until=? WHERE user_id=7",java.time.LocalDateTime.now().minusMinutes(1));
        service.muteMember(21L,9L,7L,20);
        // Simulate a worker holding an old candidate after an administrator extended it.
        tx.executeWithoutResult(status -> new AccessMutationRecorder(jdbc,journal,new ConversationWriteGuard(jdbc),true)
                .reconcileExpiredMute("group:21",7L));
        assertEquals(List.of("SEND_DENIED","UPDATED","SEND_DENIED"),jdbc.queryForList("SELECT reason FROM recovery_mutation ORDER BY mutation_cursor",String.class));
        assertEquals(4L,jdbc.queryForObject("SELECT access_version FROM recovery_access_state",Long.class));
        assertEquals(false,jdbc.queryForObject("SELECT send_allowed FROM recovery_access_state",Boolean.class));
    }

    @Test
    void expiryAndMemberRemovalShareAtomicVersionChain() {
        seedTerminalMessage();
        var service = groupRemovalService(realGroupConversationService());
        service.muteMember(21L,9L,7L,10);
        jdbc.update("UPDATE group_member SET mute_until=? WHERE user_id=7",java.time.LocalDateTime.now().minusMinutes(1));
        assertTrue(service.removeMember(21L,9L,7L));
        expiryWorker().reconcile();
        assertEquals(List.of("SEND_DENIED","UPDATED","REMOVED"),jdbc.queryForList("SELECT reason FROM recovery_mutation ORDER BY mutation_cursor",String.class));
        assertEquals(4L,jdbc.queryForObject("SELECT access_version FROM recovery_access_state",Long.class));
        assertEquals(false,jdbc.queryForObject("SELECT read_allowed FROM recovery_access_state",Boolean.class));
    }

    @Test
    void failedExpiryRemainsRetryableAndDoesNotPartiallyAdvanceVersion() {
        seedTerminalMessage();
        var service = groupRemovalService(realGroupConversationService());
        service.muteMember(21L,9L,7L,10);
        jdbc.update("UPDATE group_member SET mute_until=? WHERE user_id=7",java.time.LocalDateTime.now().minusMinutes(1));
        jdbc.update("UPDATE recovery_user_stream SET latest_cursor=? WHERE user_id=7",Long.MAX_VALUE);
        expiryWorker().reconcile();
        assertEquals(2L,jdbc.queryForObject("SELECT access_version FROM recovery_access_state",Long.class));
        assertEquals(false,jdbc.queryForObject("SELECT send_allowed FROM recovery_access_state",Boolean.class));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
    }

    @Test
    void concurrentExpiryCandidatesCommitOnlyOneTransition() throws Exception {
        seedTerminalMessage();
        groupRemovalService(realGroupConversationService()).muteMember(21L,9L,7L,10);
        jdbc.update("UPDATE group_member SET mute_until=? WHERE user_id=7",java.time.LocalDateTime.now().minusMinutes(1));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int n=0;n<2;n++) tasks.add(pool.submit(() -> {
                start.await();
                tx.executeWithoutResult(status -> new AccessMutationRecorder(jdbc,journal,new ConversationWriteGuard(jdbc),true)
                        .reconcileExpiredMute("group:21",7L));
                return null;
            }));
            start.countDown();
            for (var task : tasks) task.get(10,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(3L,jdbc.queryForObject("SELECT access_version FROM recovery_access_state",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT latest_cursor FROM recovery_user_stream",Long.class));
    }

    @Test
    void failedRemuteRollsBackItsPrecedingExpiryFactAndNewDeadline() {
        seedTerminalMessage();
        var service = groupRemovalService(realGroupConversationService());
        service.muteMember(21L,9L,7L,10);
        jdbc.update("UPDATE group_member SET mute_until=? WHERE user_id=7",java.time.LocalDateTime.now().minusMinutes(1));
        var deadline = jdbc.queryForObject("SELECT mute_until FROM group_member WHERE user_id=7",java.sql.Timestamp.class);
        jdbc.update("UPDATE recovery_user_stream SET latest_cursor=? WHERE user_id=7",Long.MAX_VALUE-1);
        assertThrows(RuntimeException.class,()->service.muteMember(21L,9L,7L,20));
        assertEquals(deadline,jdbc.queryForObject("SELECT mute_until FROM group_member WHERE user_id=7",java.sql.Timestamp.class));
        assertEquals(2L,jdbc.queryForObject("SELECT access_version FROM recovery_access_state",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox",Long.class));
    }

    private com.lanchat.service.TemporaryRoomService realTemporaryService() {
        var service = new com.lanchat.service.impl.TemporaryRoomServiceImpl(
                sqlSession.getMapper(com.lanchat.mapper.TemporaryRoomMapper.class),realGroupConversationService(),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service,"accessMutationRecorder",new AccessMutationRecorder(jdbc,journal,new ConversationWriteGuard(jdbc),true));
        ReflectionTestUtils.setField(service,"expiryService",transactionalProxy(new TemporaryRoomExpiryService(
                sqlSession.getMapper(com.lanchat.mapper.TemporaryRoomMapper.class),realGroupConversationService(),
                new AccessMutationRecorder(jdbc,journal,new ConversationWriteGuard(jdbc),true),
                mock(org.springframework.context.ApplicationEventPublisher.class))));
        return (com.lanchat.service.TemporaryRoomService) transactionalProxy(service);
    }

    private com.lanchat.dto.TemporaryRoomCreateDTO temporaryDto() {
        var dto = new com.lanchat.dto.TemporaryRoomCreateDTO();
        dto.setRoomName("恢复临时房间");dto.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        dto.setMaxMembers(2); return dto;
    }

    @Test
    void temporaryCreateJoinLeaveRejoinUsesDurableAccessAndCurrentCapacity() {
        var service = realTemporaryService();
        var room = service.createRoom(9L,temporaryDto());
        service.joinByCode(7L,room.getRoomCode());
        assertThrows(IllegalArgumentException.class,()->service.joinByCode(11L,room.getRoomCode()));
        assertThrows(IllegalArgumentException.class,()->service.leaveRoom(room.getId(),9L));
        service.leaveRoom(room.getId(),7L);
        service.joinByCode(7L,room.getRoomCode());
        service.joinByCode(7L,room.getRoomCode());
        assertEquals(List.of("GRANTED","REMOVED","GRANTED"),jdbc.queryForList("SELECT reason FROM recovery_mutation WHERE user_id=7 ORDER BY mutation_cursor",String.class));
        assertEquals(List.of(2L,3L,4L),jdbc.queryForList("SELECT access_version FROM recovery_mutation WHERE user_id=7 ORDER BY mutation_cursor",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member WHERE left_time IS NULL",Long.class));
        assertEquals(4L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox",Long.class));
    }

    @Test
    void temporaryJoinAndLeaveRollbackTheirMembershipOnJournalFailure() {
        var service = realTemporaryService();
        var room = service.createRoom(9L,temporaryDto());
        jdbc.update("INSERT INTO recovery_user_stream VALUES (?,?,?,0)",7L,UUID.randomUUID().toString(),Long.MAX_VALUE);
        assertThrows(RuntimeException.class,()->service.joinByCode(7L,room.getRoomCode()));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member WHERE user_id=7",Long.class));
        // Restore only the deliberately fault-injected fixture counter; no business reset path.
        jdbc.update("DELETE FROM recovery_user_stream WHERE user_id=7");
        service.joinByCode(7L,room.getRoomCode());
        jdbc.update("UPDATE recovery_user_stream SET latest_cursor=? WHERE user_id=7",Long.MAX_VALUE);
        assertThrows(RuntimeException.class,()->service.leaveRoom(room.getId(),7L));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member WHERE user_id=7 AND left_time IS NULL",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT access_version FROM recovery_access_state WHERE user_id=7",Long.class));
    }

    @Test
    void temporaryExpiryFreezeArchiveDestroyPublishDurablePermissionsOnce() {
        var service = realTemporaryService();
        for (String action : List.of("FREEZE","ARCHIVE","DESTROY")) {
            var dto = temporaryDto(); dto.setExpireAction(action);
            var room = service.createRoom(9L,dto);
            service.joinByCode(7L,room.getRoomCode());
            jdbc.update("UPDATE temporary_room SET expires_at=? WHERE id=?",java.time.LocalDateTime.now().minusMinutes(1),room.getId());
            assertEquals(1,service.processExpiredRooms(java.time.LocalDateTime.now()));
            assertEquals(0,service.processExpiredRooms(java.time.LocalDateTime.now()));
            String cid = room.getConversationId();
            assertEquals("DESTROY".equals(action) ? "DESTROYED" : "ARCHIVE".equals(action) ? "ARCHIVED" : "FROZEN",
                    jdbc.queryForObject("SELECT status FROM temporary_room WHERE id=?",String.class,room.getId()));
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_access_state WHERE conversation_id=? AND send_allowed=TRUE",Long.class,cid));
            assertEquals("DESTROY".equals(action) ? 0L : 2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_access_state WHERE conversation_id=? AND read_allowed=TRUE",Long.class,cid));
            assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE conversation_id=? AND reason='SEND_DENIED'",Long.class,cid));
            if ("DESTROY".equals(action)) {
                assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE conversation_id=? AND reason='DESTROYED' AND access_version=4",Long.class,cid));
                assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member WHERE conversation_id=? AND left_time IS NULL",Long.class,cid));
            }
        }
    }

    @Test
    void laterRoomFailureDoesNotRollbackEarlierRoomButRollsBackItsOwnMembersAndLogs() {
        var service = realTemporaryService();
        var first = service.createRoom(7L,temporaryDto());
        var dto = temporaryDto();dto.setExpireAction("DESTROY");
        var second = service.createRoom(9L,dto);
        jdbc.update("UPDATE temporary_room SET expires_at=?",java.time.LocalDateTime.now().minusMinutes(1));
        jdbc.update("UPDATE recovery_user_stream SET latest_cursor=? WHERE user_id=9",Long.MAX_VALUE-1);
        assertThrows(RuntimeException.class,()->service.processExpiredRooms(java.time.LocalDateTime.now()));
        assertEquals("FROZEN",jdbc.queryForObject("SELECT status FROM temporary_room WHERE id=?",String.class,first.getId()));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM temporary_room WHERE id=?",String.class,second.getId()));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member WHERE conversation_id=? AND left_time IS NULL",Long.class,second.getConversationId()));
        assertEquals(2L,jdbc.queryForObject("SELECT access_version FROM recovery_access_state WHERE user_id=9",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE user_id=9",Long.class));
    }

    @Test
    void burnedFileMessageCannotGrantDownloadButAnotherLiveReferenceStillCan() {
        seedTerminalMessage();
        jdbc.update("UPDATE chat_message SET file_path='0123456789abcdef0123456789abcdef.pdf' WHERE message_id='terminal'");
        var metadata = new com.lanchat.entity.FileMetadata();
        metadata.setId(1L);metadata.setUploadUserId(99L);metadata.setFilePath("0123456789abcdef0123456789abcdef.pdf");
        var files = spy(new com.lanchat.service.impl.FileServiceImpl());
        doReturn(metadata).when(files).getByStoredName("0123456789abcdef0123456789abcdef.pdf");
        ReflectionTestUtils.setField(files,"chatMessageMapper",sqlSession.getMapper(ChatMessageMapper.class));
        ReflectionTestUtils.setField(files,"fileAccessGrantMapper",mock(com.lanchat.mapper.FileAccessGrantMapper.class));
        ReflectionTestUtils.setField(files,"userMapper",mock(com.lanchat.mapper.UserMapper.class));
        ReflectionTestUtils.setField(files,"chatGroupMapper",mock(com.lanchat.mapper.ChatGroupMapper.class));
        var conversations = mock(ConversationService.class);
        when(conversations.canDownloadFile("group:21",9L)).thenReturn(true);
        ReflectionTestUtils.setField(files,"conversationService",conversations);
        assertTrue(files.canAccessFile("0123456789abcdef0123456789abcdef.pdf",9L));
        terminalService(true).markAsBurned("terminal",9L);
        assertFalse(files.canAccessFile("0123456789abcdef0123456789abcdef.pdf",9L));
        jdbc.update("INSERT INTO chat_message(message_id,conversation_id,sequence,file_path,is_recalled,status) VALUES ('live-file','group:21',63,'0123456789abcdef0123456789abcdef.pdf',0,0)");
        assertTrue(files.canAccessFile("0123456789abcdef0123456789abcdef.pdf",9L));
        jdbc.update("UPDATE chat_message SET is_recalled=1 WHERE message_id='live-file'");
        assertFalse(files.canAccessFile("0123456789abcdef0123456789abcdef.pdf",9L));
    }

    MutationStreamReader streamReader() {
        return (MutationStreamReader)transactionalProxy(new MutationStreamReader(jdbc));
    }

    RecoveryResumeSessions resumeSessions() {
        return (RecoveryResumeSessions)transactionalProxy(new RecoveryResumeSessions(jdbc,streamReader()));
    }

    private String appendRecoveryFacts(int count) {
        for (int i=0;i<count;i++) tx.executeWithoutResult(status -> journal.append(UUID.randomUUID(),List.of(7L),RECALLED));
        return streamReader().stream(7L).epoch();
    }

    private void recoveryFault(int status,String reason,org.junit.jupiter.api.function.Executable action) {
        RecoveryFault fault=assertThrows(RecoveryFault.class,action);
        assertEquals(status,fault.status());assertEquals(reason,fault.reason());
    }

    @Test
    void recoveryPagesAreContinuousAndBoundToTheirOwnerAndEpoch() {
        String epoch=appendRecoveryFacts(3);var reader=streamReader();
        var first=reader.read(7,epoch,"0","3",2);
        assertEquals(List.of("1","2"),first.records().stream().map(MutationStreamReader.Record::cursor).toList());
        assertTrue(first.hasMore());assertEquals("2",first.nextCursor());
        assertTrue(first.records().stream().allMatch(record->record.recordVersion()==1));
        var last=reader.read(7,epoch,first.nextCursor(),"3",2);
        assertFalse(last.hasMore());assertEquals("3",last.nextCursor());
        assertEquals(1,last.records().size());assertEquals("2",last.records().get(0).objectVersion());
        assertTrue(reader.read(7,epoch,"3","3",2).records().isEmpty());
        tx.executeWithoutResult(status -> journal.append(UUID.randomUUID(),List.of(9L),RECALLED));
        recoveryFault(409,"STREAM_RESET",()->reader.read(9,epoch,"0","1",2));
        recoveryFault(409,"CURSOR_AHEAD",()->reader.read(7,epoch,"0","4",2));
        jdbc.update("UPDATE recovery_user_stream SET floor_cursor=1 WHERE user_id=7");
        recoveryFault(409,"CURSOR_EXPIRED",()->reader.read(7,epoch,"0","3",2));
    }

    @Test
    void recoveryReaderRejectsMissingOrMalformedFactsInsteadOfReturningSuccess() {
        String epoch=appendRecoveryFacts(3);var reader=streamReader();
        jdbc.update("DELETE FROM recovery_mutation WHERE user_id=7 AND mutation_cursor=2");
        recoveryFault(503,"RECOVERY_UNAVAILABLE",()->reader.read(7,epoch,"0","3",200));
        jdbc.update("UPDATE recovery_mutation SET mutation_type='UNKNOWN' WHERE user_id=7 AND mutation_cursor=1");
        recoveryFault(503,"RECOVERY_UNAVAILABLE",()->reader.read(7,epoch,"0","1",200));
        assertThrows(IllegalStateException.class,()->new MutationStreamReader(jdbc).read(7,epoch,"0","0",1));
    }

    @Test
    void recoveryCursorParsingNeverUsesFloatingPointOrAcceptsNoncanonicalNumbers() {
        assertEquals(Long.MAX_VALUE,MutationStreamReader.cursor("9223372036854775807"));
        for (String invalid:List.of("","00","01","-1","+1","1.0","1e2"," 1","9223372036854775808")) {
            recoveryFault(400,"INVALID_CURSOR",()->MutationStreamReader.cursor(invalid));
        }
        String epoch=appendRecoveryFacts(1);
        jdbc.update("UPDATE recovery_user_stream SET latest_cursor=?,floor_cursor=? WHERE user_id=7",Long.MAX_VALUE,Long.MAX_VALUE-1);
        jdbc.update("UPDATE recovery_mutation SET mutation_cursor=? WHERE user_id=7",Long.MAX_VALUE);
        var page=streamReader().read(7,epoch,Long.toString(Long.MAX_VALUE-1),Long.toString(Long.MAX_VALUE),1);
        assertEquals(Long.toString(Long.MAX_VALUE),page.nextCursor());assertFalse(page.hasMore());
    }

    @Test
    void resumeCutStaysFixedUntilReadyAndCatchesWritesDuringCatchup() {
        String epoch=appendRecoveryFacts(1);var service=resumeSessions();
        var session=service.open(7,"https://meshx.test",epoch,"0",null);
        assertEquals("1",service.cut(7,"https://meshx.test",session.recoveryId()).through());
        appendRecoveryFacts(1);
        assertEquals("1",service.cut(7,"https://meshx.test",session.recoveryId()).through());
        assertEquals("1",service.mutations(7,"https://meshx.test",session.recoveryId(),"0","1",200).nextCursor());
        assertFalse(service.ready(7,"https://meshx.test",session.recoveryId(),"1").ready());
        assertEquals("2",service.cut(7,"https://meshx.test",session.recoveryId()).through());
        recoveryFault(400,"RANGE_MISMATCH",()->service.ready(7,"https://meshx.test",session.recoveryId(),"1"));
        assertTrue(service.ready(7,"https://meshx.test",session.recoveryId(),"2").ready());
        service.release(7,"https://meshx.test",session.recoveryId());
        recoveryFault(404,"RECOVERY_NOT_FOUND",()->service.cut(7,"https://meshx.test",session.recoveryId()));
    }

    @Test
    void resumeSessionEnforcesOwnerOriginEpochExpiryAndCutRange() {
        String epoch=appendRecoveryFacts(2);var service=resumeSessions();
        var session=service.open(7,"https://meshx.test",epoch,"1",null);String id=session.recoveryId();
        recoveryFault(404,"RECOVERY_NOT_FOUND",()->service.cut(9,"https://meshx.test",id));
        recoveryFault(404,"RECOVERY_NOT_FOUND",()->service.release(7,"https://other.test",id));
        recoveryFault(400,"RANGE_MISMATCH",()->service.mutations(7,"https://meshx.test",id,"1","2",1));
        service.cut(7,"https://meshx.test",id);
        recoveryFault(400,"RANGE_MISMATCH",()->service.mutations(7,"https://meshx.test",id,"0","2",1));
        jdbc.update("UPDATE recovery_user_stream SET stream_epoch=? WHERE user_id=7",UUID.randomUUID().toString());
        recoveryFault(409,"STREAM_RESET",()->service.cut(7,"https://meshx.test",id));
        jdbc.update("UPDATE recovery_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",id);
        recoveryFault(410,"SESSION_EXPIRED",()->service.cut(7,"https://meshx.test",id));
    }

    @Test
    void resumeIdempotencyReusesOnlyTheIdenticalRequest() {
        String epoch=appendRecoveryFacts(2);var service=resumeSessions();
        var session=service.open(7,"https://meshx.test",epoch,"0","request-a");
        assertEquals(session,service.open(7,"https://meshx.test",epoch,"0","request-a"));
        recoveryFault(409,"IDEMPOTENCY_CONFLICT",()->service.open(7,"https://meshx.test",epoch,"1","request-a"));
        assertNotEquals(session.recoveryId(),service.open(7,"https://other.test",epoch,"0","request-a").recoveryId());
        assertEquals(64,session.recoveryId().length());
        for(String invalid:List.of("http://","https://host/","https://host:443","http://host:80","https://user@host","https://host?x=1","https://HOST","https://host:65536")) {
            recoveryFault(400,"INVALID_ORIGIN",()->service.open(7,invalid,epoch,"0",null));
        }
        assertNotNull(service.open(7,"http://[::1]:8080",epoch,"0",null));
    }

    @Test
    void concurrentResumeRetriesCreateExactlyOneSessionAndCapacityCountsLivePins() throws Exception {
        String epoch=appendRecoveryFacts(1);var pool=Executors.newFixedThreadPool(4);
        try {
            var start=new CountDownLatch(1);List<Callable<String>> calls=new ArrayList<>();
            for(int i=0;i<4;i++) calls.add(()->{start.await();return resumeSessions().open(7,"https://meshx.test",epoch,"0","same-request").recoveryId();});
            var futures=calls.stream().map(pool::submit).toList();start.countDown();
            var ids=new java.util.HashSet<String>();for(var future:futures) ids.add(future.get(20,TimeUnit.SECONDS));
            assertEquals(1,ids.size());
        } finally {pool.shutdownNow();}
        var service=resumeSessions();for(int i=1;i<20;i++) service.open(7,"https://meshx.test",epoch,"0",null);
        recoveryFault(503,"RECOVERY_CAPACITY",()->service.open(7,"https://meshx.test",epoch,"0",null));
        jdbc.update("UPDATE recovery_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
        assertNotNull(service.open(7,"https://meshx.test",epoch,"0",null));
    }

    private MutationRetention retention() {
        return (MutationRetention)transactionalProxy(new MutationRetention(jdbc));
    }

    @Test
    void retentionHonorsLiveSessionPinsAndOnlyCollectsAnOldContinuousPrefix() {
        String epoch=appendRecoveryFacts(5);var service=resumeSessions();
        jdbc.update("UPDATE recovery_mutation SET committed_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 31 DAY)");
        var session=service.open(7,"https://meshx.test",epoch,"2",null);
        assertEquals(2,retention().prune(7,200).deleted());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox",Integer.class));
        assertEquals(0,retention().prune(7,200).deleted());
        service.cut(7,"https://meshx.test",session.recoveryId());
        assertEquals(3,service.mutations(7,"https://meshx.test",session.recoveryId(),"2","5",200).records().size());
        service.release(7,"https://meshx.test",session.recoveryId());
        jdbc.update("UPDATE recovery_mutation SET committed_at=UTC_TIMESTAMP(6) WHERE mutation_cursor=4");
        assertEquals(new MutationRetention.Result(1,"3","5"),retention().prune(7,200));
        assertEquals(0,retention().prune(7,200).deleted());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Integer.class));
        recoveryFault(409,"CURSOR_EXPIRED",()->service.open(7,"https://meshx.test",epoch,"2",null));
        assertNotNull(service.open(7,"https://meshx.test",epoch,"3",null));
    }

    @Test
    void retentionRejectsGapsAtomicallyAndExpiredPinsNoLongerHoldLogs() {
        String epoch=appendRecoveryFacts(4);var service=resumeSessions();
        jdbc.update("UPDATE recovery_mutation SET committed_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 31 DAY)");
        service.open(7,"https://meshx.test",epoch,"0",null);
        jdbc.update("UPDATE recovery_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
        assertEquals(new MutationRetention.Result(1,"1","4"),retention().prune(7,1));
        jdbc.update("DELETE FROM recovery_mutation WHERE mutation_cursor=3");
        recoveryFault(503,"RECOVERY_UNAVAILABLE",()->retention().prune(7,200));
        assertEquals("1",Long.toString(streamReader().stream(7).floor()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Integer.class));
    }

    @Test
    void retentionWaitsForAnUncommittedPinAndThenProtectsItsRange() throws Exception {
        String epoch=appendRecoveryFacts(3);
        jdbc.update("UPDATE recovery_mutation SET committed_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 31 DAY)");
        var pool=Executors.newFixedThreadPool(2);var pinned=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try {
            var creator=pool.submit(()->tx.execute(status->{
                var session=resumeSessions().open(7,"https://meshx.test",epoch,"1",null);
                pinned.countDown();
                try {assertTrue(commit.await(20,TimeUnit.SECONDS));} catch(InterruptedException error) {throw new RuntimeException(error);}
                return session;
            }));
            assertTrue(pinned.await(20,TimeUnit.SECONDS));
            var collector=pool.submit(()->retention().prune(7,200));
            commit.countDown();
            var session=creator.get(20,TimeUnit.SECONDS);
            assertEquals(new MutationRetention.Result(1,"1","3"),collector.get(20,TimeUnit.SECONDS));
            resumeSessions().cut(7,"https://meshx.test",session.recoveryId());
            assertEquals(2,resumeSessions().mutations(7,"https://meshx.test",session.recoveryId(),"1","3",200).records().size());
        } finally {commit.countDown();pool.shutdownNow();}
    }

    @Test
    void retentionWorkerReleasesExpiredSessionsAndRevisitsUsersWithoutLosingFailedPrefixes() {
        String epoch=appendRecoveryFacts(3);
        resumeSessions().open(7,"https://meshx.test",epoch,"0",null);
        jdbc.update("UPDATE recovery_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
        jdbc.update("UPDATE recovery_mutation SET committed_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 31 DAY)");
        var worker=new MutationRetentionWorker(jdbc,retention());worker.collect();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_session",Integer.class));
        assertEquals(3,streamReader().stream(7).floor());
        appendRecoveryFacts(1);worker.collect(); // end of scan resets the keyset
        worker.collect();assertEquals(3,streamReader().stream(7).floor()); // recent event survives
        jdbc.update("UPDATE recovery_mutation SET committed_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 31 DAY)");
        worker.collect();worker.collect();assertEquals(4,streamReader().stream(7).floor());
    }

    @Test
    void cachedResumeCannotBypassAnEpochReset() {
        String epoch=appendRecoveryFacts(1);var service=resumeSessions();
        service.open(7,"https://meshx.test",epoch,"0","stable-key");
        jdbc.update("UPDATE recovery_user_stream SET stream_epoch=? WHERE user_id=7",UUID.randomUUID().toString());
        recoveryFault(409,"STREAM_RESET",()->service.open(7,"https://meshx.test",epoch,"0","stable-key"));
        assertThrows(IllegalStateException.class,()->new RecoveryResumeSessions(jdbc,streamReader()).open(7,"https://meshx.test",epoch,"0",null));
    }

    RecoverySnapshotManifest manifests() {
        return (RecoverySnapshotManifest)transactionalProxy(new RecoverySnapshotManifest(jdbc,streamReader()));
    }

    void manifestConversation(String cid,int count) {
        jdbc.update("INSERT INTO group_member(group_id,user_id,role) VALUES (?,7,0)",Long.parseLong(cid.substring(6)));
        jdbc.update("INSERT INTO conversation(id,type,last_sequence,status) VALUES (?,'GROUP',?,'ACTIVE')",cid,count);
        jdbc.update("INSERT INTO conversation_member(conversation_id,user_id,role) VALUES (?,7,'MEMBER')",cid);
        jdbc.update("INSERT INTO recovery_access_state VALUES (?,7,1,TRUE,TRUE)",cid);
        List<Object[]> rows=new ArrayList<>();
        for(int i=1;i<=count;i++) rows.add(new Object[]{cid+"-"+i,cid,i,1,"NORMAL"});
        jdbc.batchUpdate("INSERT INTO recovery_message_state VALUES (?,?,?,?,?)",rows);
        List<Object[]> messages=new ArrayList<>();
        for(int i=1;i<=count;i++) messages.add(new Object[]{cid+"-"+i,cid,i});
        jdbc.batchUpdate("INSERT INTO chat_message(message_id,conversation_id,sequence,is_recalled,status,from_user_id,type,content) VALUES (?,?,?,0,0,7,'text','synthetic manifest body')",messages);
        jdbc.update("UPDATE chat_message SET create_time=?,is_burn=0 WHERE conversation_id=?",java.time.LocalDateTime.now(),cid);
    }

    @Test
    void manifestIncludesAllRetainedIdsBeyondOnePageAndKeepsAStableBodyFreeOrder() {
        String epoch=appendRecoveryFacts(2);manifestConversation("group:21",205);manifestConversation("group:22",2);
        var snapshot=manifests().create(7,"https://meshx.test");
        assertEquals(epoch,snapshot.streamEpoch());assertEquals("2",snapshot.startCursor());assertEquals(209,snapshot.itemCount());
        assertEquals(List.of("CONVERSATION","CONVERSATION","MESSAGE"),jdbc.queryForList(
                "SELECT kind FROM recovery_snapshot_item WHERE session_id=? ORDER BY position LIMIT 3",String.class,snapshot.recoveryId()));
        assertEquals(205,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot_item WHERE session_id=? AND kind='MESSAGE' AND conversation_id='group:21'",Integer.class,snapshot.recoveryId()));
        assertEquals(209,jdbc.queryForObject("SELECT COUNT(DISTINCT page_token) FROM recovery_snapshot_item WHERE session_id=?",Integer.class,snapshot.recoveryId()));
        resumeSessions().cut(7,"https://meshx.test",snapshot.recoveryId());
        recoveryFault(409,"SNAPSHOT_INCOMPLETE",()->resumeSessions().ready(7,"https://meshx.test",snapshot.recoveryId(),"2"));
        jdbc.update("UPDATE recovery_message_state SET state='RECALLED',object_version=2 WHERE message_id='group:21-1'");
        assertEquals("NORMAL",jdbc.queryForObject("SELECT state FROM recovery_snapshot_item WHERE session_id=? AND message_id='group:21-1'",String.class,snapshot.recoveryId()));
        // Manifest headers are immutable; page projection must re-authorize and resolve current state.
        resumeSessions().release(7,"https://meshx.test",snapshot.recoveryId());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot_item",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot",Integer.class));
    }

    @Test
    void emptyManifestCreatesARealZeroStreamAndDoesNotLeakAnotherAccountsDirectory() {
        manifestConversation("group:21",1);
        var snapshot=manifests().create(9,"https://meshx.test");
        assertEquals("0",snapshot.startCursor());assertEquals(0,snapshot.itemCount());
        assertEquals(snapshot.streamEpoch(),streamReader().stream(9).epoch());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot",Integer.class));
    }

    @Test
    void manifestRejectsIncompleteBackfillAndRollsBackItsStreamAndPin() {
        manifestConversation("group:21",1);
        jdbc.update("DELETE FROM recovery_access_state");
        recoveryFault(503,"RECOVERY_UNAVAILABLE",()->manifests().create(7,"https://meshx.test"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_session",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_user_stream",Integer.class));
        jdbc.update("INSERT INTO recovery_access_state VALUES ('group:21',7,1,TRUE,TRUE)");
        jdbc.update("INSERT INTO chat_message(message_id,conversation_id,sequence,is_recalled,status) VALUES ('missing','group:21',1,0,0)");
        recoveryFault(503,"RECOVERY_UNAVAILABLE",()->manifests().create(7,"https://meshx.test"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot_item",Integer.class));
    }

    @Test
    void manifestCounterDirectoryAndIdsUseOneSnapshotWhileNormalMessagesCommit() {
        appendRecoveryFacts(1);manifestConversation("group:21",1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var reader=new MutationStreamReader(jdbc) {
                @Override public Stream stream(long user) {
                    var boundary=super.stream(user); // establishes the actual MySQL read view
                    try {
                        pool.submit(()->tx.executeWithoutResult(status->{
                            jdbc.update("UPDATE conversation SET last_sequence=2 WHERE id='group:21'");
                            jdbc.update("INSERT INTO recovery_message_state VALUES ('later','group:21',2,1,'NORMAL')");
                            jdbc.update("INSERT INTO chat_message(message_id,conversation_id,sequence,is_recalled,status) VALUES ('later','group:21',2,0,0)");
                        })).get(20,TimeUnit.SECONDS);
                    } catch(Exception failure) {throw new RuntimeException(failure);}
                    return boundary;
                }
            };
            var service=(RecoverySnapshotManifest)transactionalProxy(new RecoverySnapshotManifest(jdbc,reader));
            var snapshot=service.create(7,"https://meshx.test");
            assertEquals(2,snapshot.itemCount());
            assertEquals(1,jdbc.queryForObject("SELECT message_sequence FROM recovery_snapshot_item WHERE session_id=? AND kind='CONVERSATION'",Long.class,snapshot.recoveryId()));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot_item WHERE session_id=? AND message_id='later'",Integer.class,snapshot.recoveryId()));
            assertEquals(2,jdbc.queryForObject("SELECT last_sequence FROM conversation WHERE id='group:21'",Long.class));
        } finally {pool.shutdownNow();}
    }

    RecoverySnapshotPages snapshotPages() {
        return (RecoverySnapshotPages)transactionalProxy(new RecoverySnapshotPages(jdbc,new ConversationWriteGuard(jdbc),streamReader()));
    }

    @Test
    void snapshotPagingCompletesEveryPositionBeforeReadyAndRetriesProjectCurrentRecall() {
        manifestConversation("group:21",205);var snapshot=manifests().create(7,"https://meshx.test");
        var pages=snapshotPages();var sessions=resumeSessions();String id=snapshot.recoveryId();
        sessions.cut(7,"https://meshx.test",id);
        recoveryFault(409,"SNAPSHOT_INCOMPLETE",()->sessions.ready(7,"https://meshx.test",id,"0",true));
        var first=pages.page(7,"https://meshx.test",id,null,100);
        assertEquals(100,first.items().size());assertFalse(first.snapshotComplete());
        assertEquals("synthetic manifest body",first.items().get(1).content());
        assertEquals(7L,first.items().get(1).details().fromUserId());
        assertEquals("text",first.items().get(1).details().contentType());
        assertNotNull(first.items().get(1).details().createTime());
        assertEquals(0,first.items().get(1).details().isBurn());
        assertTrue(terminalService(true).recallMessage("group:21-1",7L));
        var retry=pages.page(7,"https://meshx.test",id,null,100);
        assertEquals(first.nextPageToken(),retry.nextPageToken());
        assertEquals(first.items().stream().map(RecoverySnapshotPages.Item::messageId).toList(),retry.items().stream().map(RecoverySnapshotPages.Item::messageId).toList());
        assertEquals("RECALLED",retry.items().get(1).state());assertNull(retry.items().get(1).content());
        assertNull(retry.items().get(1).details());
        assertEquals("2",retry.items().get(1).objectVersion());
        var second=pages.page(7,"https://meshx.test",id,first.nextPageToken(),100);
        var last=pages.page(7,"https://meshx.test",id,second.nextPageToken(),100);
        assertEquals(6,last.items().size());assertTrue(last.snapshotComplete());assertNull(last.nextPageToken());
        recoveryFault(409,"SNAPSHOT_INCOMPLETE",()->sessions.ready(7,"https://meshx.test",id,"0",false));
        assertFalse(sessions.ready(7,"https://meshx.test",id,"0",true).ready());
        String cut=sessions.cut(7,"https://meshx.test",id).through();
        assertEquals("1",cut);
        assertEquals(1,sessions.mutations(7,"https://meshx.test",id,"0",cut,200).records().size());
        assertTrue(sessions.ready(7,"https://meshx.test",id,cut,true).ready());
    }

    @Test
    void snapshotDetailsPreserveRenderingDataAndRejectIncompleteNormalRows() {
        manifestConversation("group:21",1);
        jdbc.update("UPDATE chat_message SET type='image',client_msg_id='client-image',is_burn=1,burn_duration=12,reply_to_id='reply-id',mention_user_ids='8,9' WHERE message_id='group:21-1'");
        var snapshot=manifests().create(7,"https://meshx.test");
        var item=snapshotPages().page(7,"https://meshx.test",snapshot.recoveryId(),null,100).items().get(1);
        assertEquals("image",item.details().contentType());assertEquals("client-image",item.details().clientMsgId());
        assertEquals(1,item.details().isBurn());assertEquals(12,item.details().burnDuration());
        assertEquals("reply-id",item.details().replyToId());assertEquals("8,9",item.details().mentionUserIds());
        jdbc.update("UPDATE chat_message SET create_time=NULL WHERE message_id='group:21-1'");
        var invalid=manifests().create(7,"https://meshx.test");
        recoveryFault(503,"RECOVERY_UNAVAILABLE",()->snapshotPages().page(7,"https://meshx.test",invalid.recoveryId(),null,100));
        assertEquals(0L,jdbc.queryForObject("SELECT served_through FROM recovery_snapshot WHERE session_id=?",Long.class,invalid.recoveryId()));
    }

    @Test
    void snapshotRevocationReturnsOnlyConversationInvalidationWithoutInventingGlobalMessageState() {
        manifestConversation("group:21",2);
        jdbc.update("INSERT INTO chat_group(id,owner_id,max_members) VALUES (21,9,200)");
        jdbc.update("INSERT INTO group_member(group_id,user_id,role) VALUES (21,9,2)");
        var snapshot=manifests().create(7,"https://meshx.test");var pages=snapshotPages();String id=snapshot.recoveryId();
        var first=pages.page(7,"https://meshx.test",id,null,1);
        assertTrue(groupRemovalService(realGroupConversationService()).removeMember(21L,9L,7L));
        var revoked=pages.page(7,"https://meshx.test",id,first.nextPageToken(),200);
        assertTrue(revoked.snapshotComplete());assertEquals(2,revoked.items().size());
        for(var item:revoked.items()) {
            assertEquals("CONVERSATION",item.kind());assertEquals("group:21",item.conversationId());
            assertFalse(item.readAllowed());assertFalse(item.sendAllowed());assertEquals("2",item.accessVersion());
            assertNull(item.content());assertNull(item.messageId());assertNull(item.state());
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_message_state WHERE state='NORMAL'",Integer.class));
    }

    @Test
    void snapshotMissingPhysicalMessageUsesDurableTombstoneAndNeverSilentlyDropsItsId() {
        manifestConversation("group:21",1);var snapshot=manifests().create(7,"https://meshx.test");
        tx.executeWithoutResult(status->{
            jdbc.update("UPDATE recovery_message_state SET state='UNAVAILABLE',object_version=2 WHERE message_id='group:21-1'");
            jdbc.update("DELETE FROM chat_message WHERE message_id='group:21-1'");
            journal.append(UUID.randomUUID(),List.of(7L),MutationFact.message(MutationFact.Type.MESSAGE_UNAVAILABLE,"group:21","group:21-1",2));
        });
        var page=snapshotPages().page(7,"https://meshx.test",snapshot.recoveryId(),null,200);
        assertEquals(2,page.items().size());var item=page.items().get(1);
        assertEquals("group:21-1",item.messageId());assertEquals("UNAVAILABLE",item.state());assertEquals("2",item.objectVersion());assertNull(item.content());
    }

    @Test
    void snapshotTokensCannotCrossSessionsSkipUnservedPositionsOrBypassOwnership() {
        manifestConversation("group:21",3);var one=manifests().create(7,"https://meshx.test");var two=manifests().create(7,"https://meshx.test");
        var pages=snapshotPages();var first=pages.page(7,"https://meshx.test",one.recoveryId(),null,1);
        recoveryFault(400,"INVALID_PAGE_TOKEN",()->pages.page(7,"https://meshx.test",two.recoveryId(),first.nextPageToken(),1));
        String unseen=jdbc.queryForObject("SELECT page_token FROM recovery_snapshot_item WHERE session_id=? AND position=3",String.class,one.recoveryId());
        recoveryFault(400,"INVALID_PAGE_TOKEN",()->pages.page(7,"https://meshx.test",one.recoveryId(),unseen,1));
        recoveryFault(404,"RECOVERY_NOT_FOUND",()->pages.page(9,"https://meshx.test",one.recoveryId(),null,1));
        recoveryFault(404,"RECOVERY_NOT_FOUND",()->pages.page(7,"https://other.test",one.recoveryId(),null,1));
    }

    @Test
    void snapshotProjectionFailureDoesNotAdvanceProgressAndEmptySnapshotRequiresARead() {
        manifestConversation("group:21",1);var snapshot=manifests().create(7,"https://meshx.test");
        jdbc.update("DELETE FROM recovery_message_state");
        recoveryFault(503,"RECOVERY_UNAVAILABLE",()->snapshotPages().page(7,"https://meshx.test",snapshot.recoveryId(),null,200));
        assertEquals(0,jdbc.queryForObject("SELECT served_through FROM recovery_snapshot WHERE session_id=?",Long.class,snapshot.recoveryId()));
        var empty=manifests().create(9,"https://meshx.test");var sessions=resumeSessions();sessions.cut(9,"https://meshx.test",empty.recoveryId());
        recoveryFault(409,"SNAPSHOT_INCOMPLETE",()->sessions.ready(9,"https://meshx.test",empty.recoveryId(),"0",true));
        assertTrue(snapshotPages().page(9,"https://meshx.test",empty.recoveryId(),null,200).snapshotComplete());
        assertTrue(sessions.ready(9,"https://meshx.test",empty.recoveryId(),"0",true).ready());
        jdbc.update("DELETE FROM recovery_snapshot WHERE session_id=?",empty.recoveryId());
        recoveryFault(410,"SNAPSHOT_EXPIRED",()->sessions.ready(9,"https://meshx.test",empty.recoveryId(),"0",true));
    }

    @Test
    void rebuildIdempotencyReturnsTheOriginalManifestAndRejectsModeReuse() {
        manifestConversation("group:21",1);var service=manifests();
        var first=service.create(7,"https://meshx.test","rebuild-request");
        assertEquals(first,service.create(7,"https://meshx.test","rebuild-request"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot",Integer.class));
        recoveryFault(409,"IDEMPOTENCY_CONFLICT",()->resumeSessions().open(7,"https://meshx.test",first.streamEpoch(),"0","rebuild-request"));
    }

    @Test
    void actualRecoveryHttpRebuildPagesCutReadyAndReleaseShareTheDatabaseProtocol() throws Exception {
        manifestConversation("group:21",2);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.lanchat.controller.RecoveryController(resumeSessions(),manifests(),snapshotPages(),streamReader(),true,true)).build();
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new com.lanchat.security.LoginUser(7L,"synthetic","web"),null,List.of()));
        try {
            var opened=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/chat/recovery/sessions")
                            .contentType("application/json").content("{\"protocolVersion\":1,\"mode\":\"rebuild\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
            var data=mapper.readTree(opened.getContentAsString()).get("data");String id=data.get("recoveryId").asText();
            assertEquals("rebuild",data.get("mode").asText());assertEquals("0",data.get("snapshotBoundary").asText());
            String base="/api/v1/chat/recovery/sessions/"+id;
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/snapshot"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.snapshotComplete").value(true))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[1].content").value("synthetic manifest body"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base+"/cut"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base+"/ready").contentType("application/json")
                            .content("{\"appliedCursor\":\"0\",\"snapshotComplete\":true}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.ready").value(true));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_snapshot_item",Integer.class));
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }

    @Test
    void localRebuildOnlyCapturesSelectedConversationsButKeepsTheUserMutationBoundary() {
        String epoch=appendRecoveryFacts(2);manifestConversation("group:21",2);manifestConversation("group:22",3);
        var snapshot=manifests().create(7,"https://meshx.test","local-request",List.of("group:22"));
        assertEquals("rebuild-conversations",snapshot.mode());assertEquals("2",snapshot.startCursor());assertEquals(epoch,snapshot.streamEpoch());
        assertEquals(4,snapshot.itemCount());
        var page=snapshotPages().page(7,"https://meshx.test",snapshot.recoveryId(),null,200);
        assertTrue(page.snapshotComplete());assertTrue(page.items().stream().allMatch(item->item.conversationId().equals("group:22")));
        appendRecoveryFacts(1);String cut=resumeSessions().cut(7,"https://meshx.test",snapshot.recoveryId()).through();
        assertEquals("3",cut);
        assertEquals("group:21",resumeSessions().mutations(7,"https://meshx.test",snapshot.recoveryId(),"2",cut,200).records().get(0).conversationId());
        assertTrue(resumeSessions().ready(7,"https://meshx.test",snapshot.recoveryId(),cut,true).ready());
    }

    @Test
    void localRebuildIncludesVersionedRevocationEvenWhenDirectoryAndConversationAreGone() {
        appendRecoveryFacts(1);
        jdbc.update("INSERT INTO recovery_access_state VALUES ('group:21',7,4,FALSE,FALSE)");
        var snapshot=manifests().create(7,"https://meshx.test",null,List.of("group:21"));
        assertEquals("rebuild-conversations",snapshot.mode());assertEquals(1,snapshot.itemCount());
        var page=snapshotPages().page(7,"https://meshx.test",snapshot.recoveryId(),null,200);
        assertTrue(page.snapshotComplete());assertEquals("4",page.items().get(0).accessVersion());
        assertFalse(page.items().get(0).readAllowed());assertNull(page.items().get(0).content());
    }

    @Test
    void unknownLocalIdExplicitlyFallsBackToWholeAccountWithoutFabricatingAnAccessVersion() {
        manifestConversation("group:21",1);manifestConversation("group:22",1);
        var snapshot=manifests().create(7,"https://meshx.test","unknown-request",List.of("group:999"));
        assertEquals("rebuild",snapshot.mode());assertEquals(4,snapshot.itemCount());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_access_state WHERE conversation_id='group:999'",Integer.class));
        assertEquals(snapshot,manifests().create(7,"https://meshx.test","unknown-request",List.of("group:999")));
    }

    @Test
    void localRebuildIdempotencyBindsCanonicalSelectionAndRejectsMalformedLists() {
        manifestConversation("group:21",1);manifestConversation("group:22",1);
        var first=manifests().create(7,"https://meshx.test","selection",List.of("group:22","group:21","group:21"));
        assertEquals(first,manifests().create(7,"https://meshx.test","selection",List.of("group:21","group:22")));
        recoveryFault(409,"IDEMPOTENCY_CONFLICT",()->manifests().create(7,"https://meshx.test","selection",List.of("group:21")));
        for(var invalid:List.of(List.<String>of(),List.of("group:01"),List.of("private:9:7"),java.util.Collections.nCopies(101,"group:21"))) {
            recoveryFault(400,"INVALID_CONVERSATIONS",()->manifests().create(7,"https://meshx.test",null,invalid));
        }
    }

    @Test
    void serializedJournalRecordsMatchFrozenRequiredFieldsAndWirePatterns() throws Exception {
        var facts=List.of(RECALLED,
                new MutationFact(MutationFact.Type.CONVERSATION_ACCESS_REVOKED,"group:21",null,null,2L,false,false,null,MutationFact.Reason.REMOVED),
                new MutationFact(MutationFact.Type.CONVERSATION_ACCESS_CHANGED,"group:21",null,null,3L,true,false,false,MutationFact.Reason.SEND_DENIED));
        for(var fact:facts)tx.executeWithoutResult(status->journal.append(UUID.randomUUID(),List.of(7L),fact));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var schemaPath=java.nio.file.Path.of(System.getenv("MESHX_RECOVERY_TEST_SQL")).getParent().getParent()
                .resolve("docs/proposals/mutation-recovery-v1/mutation-record.schema.json");
        var schema=mapper.readTree(schemaPath.toFile());
        for(var record:streamReader().read(7,streamReader().stream(7).epoch(),"0","3",200).records()) {
            var wire=mapper.readTree(mapper.writeValueAsString(record));
            com.fasterxml.jackson.databind.JsonNode branch=null;
            for(var candidate:schema.get("oneOf")) {
                for(var type:candidate.at("/properties/type/enum")) if(type.equals(wire.get("type")))branch=candidate;
            }
            assertNotNull(branch);
            for(var required:branch.get("required"))assertTrue(wire.hasNonNull(required.asText()),required.asText());
            var fields=wire.fields();
            while(fields.hasNext()) {
                var field=fields.next();var definition=branch.get("properties").get(field.getKey());
                assertNotNull(definition,"Unexpected wire field: "+field.getKey());
                if(definition.has("const"))assertEquals(definition.get("const"),field.getValue());
                if(definition.has("pattern"))assertTrue(field.getValue().asText().matches(definition.get("pattern").asText()),field.getKey());
            }
        }
    }

    @Test
    void hintDispatcherRetriesRoutingFailureWithoutLosingRecordsAndNeverHoldsTheClaimTransaction() {
        String epoch=appendRecoveryFacts(1);var router=mock(com.lanchat.cluster.RealtimeRouter.class);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(router.sendToUserWithReceipt(eq(7L),any(),any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            com.lanchat.dto.WebSocketEnvelope event=call.getArgument(1);
            assertEquals("MUTATION_AVAILABLE",event.getEvent());assertEquals(1,event.getVersion());
            assertEquals(java.util.Set.of("streamEpoch","latestCursor"),event.getPayload().keySet());
            assertEquals(epoch,event.getPayload().get("streamEpoch"));assertEquals("1",event.getPayload().get("latestCursor"));
            return calls.incrementAndGet()>1;
        });
        var dispatcher=new MutationHintDispatcher(dispatchQueue(),streamReader(),router,()->true);dispatcher.dispatch();
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM recovery_dispatch_outbox",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Integer.class));
        jdbc.update("UPDATE recovery_dispatch_outbox SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
        dispatcher.dispatch();assertEquals("DISPATCHED",jdbc.queryForObject("SELECT status FROM recovery_dispatch_outbox",String.class));
        assertEquals(2,calls.get());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Integer.class));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->dispatcher.dispatch()));
    }

    @Test
    void expiredHintCleanupFailureRollsBackTheLogPrefixAndFloor() {
        appendRecoveryFacts(2);jdbc.update("UPDATE recovery_mutation SET committed_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 31 DAY)");
        jdbc.execute("CREATE TRIGGER reject_expired_hint_cleanup BEFORE DELETE ON recovery_dispatch_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic retention failure'");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->retention().prune(7,200));
            assertEquals(0,streamReader().stream(7).floor());
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox",Integer.class));
        } finally {jdbc.execute("DROP TRIGGER reject_expired_hint_cleanup");}
        assertEquals(2,retention().prune(7,200).deleted());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox",Integer.class));
    }

    private MutationDispatchQueue dispatchQueue() {
        return (MutationDispatchQueue)transactionalProxy(new MutationDispatchQueue(jdbc));
    }

    @Test
    void concurrentDispatchClaimsNeverShareAValidLease() throws Exception {
        for (int n=0;n<12;n++) {
            tx.executeWithoutResult(status -> journal.append(UUID.randomUUID(),List.of(7L,9L),RECALLED));
        }
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<List<MutationDispatchQueue.Lease>> claim = () -> { start.await();return dispatchQueue().claim(12); };
            var first=pool.submit(claim);var second=pool.submit(claim);start.countDown();
            var leases=new ArrayList<>(first.get(10,TimeUnit.SECONDS));leases.addAll(second.get(10,TimeUnit.SECONDS));
            // SKIP LOCKED may return a short batch while another scan holds rows.
            // Once both claims commit, every remaining pending row must be claimable.
            assertFalse(leases.isEmpty());
            assertEquals(leases.size(),leases.stream().map(l -> l.userId()+":"+l.cursor()).distinct().count());
            leases.addAll(dispatchQueue().claim(200));
            assertEquals(24,leases.size());
            assertEquals(24,leases.stream().map(l -> l.userId()+":"+l.cursor()).distinct().count());
            assertTrue(dispatchQueue().claim(200).isEmpty());
            for (var lease : leases) assertTrue(dispatchQueue().acknowledge(lease));
            assertEquals(24L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox WHERE status='DISPATCHED'",Long.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void expiredDispatchLeaseCannotAcknowledgeOrReleaseNewOwner() {
        tx.executeWithoutResult(status -> journal.append(UUID.randomUUID(),List.of(7L),RECALLED));
        var queue=dispatchQueue();var old=queue.claim(1).get(0);
        jdbc.update("UPDATE recovery_dispatch_outbox SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
        assertFalse(queue.acknowledge(old));
        var current=queue.claim(1).get(0);
        assertEquals(old.eventId(),current.eventId());assertNotEquals(old.token(),current.token());
        assertEquals(2,current.attempts());
        assertFalse(queue.acknowledge(old));assertFalse(queue.retry(old));
        assertTrue(queue.acknowledge(current));assertFalse(queue.acknowledge(current));
    }

    @Test
    void failedDispatchBacksOffAndRetriesWithoutAllocatingAnotherMutation() {
        tx.executeWithoutResult(status -> journal.append(UUID.randomUUID(),List.of(7L),RECALLED));
        var queue=dispatchQueue();var first=queue.claim(1).get(0);
        assertTrue(queue.retry(first));assertTrue(queue.claim(1).isEmpty());
        jdbc.update("UPDATE recovery_dispatch_outbox SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
        var retry=queue.claim(1).get(0);assertEquals(first.eventId(),retry.eventId());assertEquals(first.cursor(),retry.cursor());
        assertTrue(queue.acknowledge(retry));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT latest_cursor FROM recovery_user_stream",Long.class));
    }

    private MutationRecoveryBootstrap bootstrap() {
        return (MutationRecoveryBootstrap)transactionalProxy(new MutationRecoveryBootstrap(jdbc,new ConversationWriteGuard(jdbc)));
    }

    @Test
    void bootstrapInitializesCurrentStatesWithoutFabricatingHistoryOrResettingStream() {
        seedTerminalMessage();
        jdbc.update("INSERT INTO chat_message(message_id,conversation_id,sequence,is_recalled,status) VALUES ('old-recall','group:21',61,1,0)");
        tx.executeWithoutResult(status -> journal.append(UUID.randomUUID(),List.of(7L),RECALLED));
        String epoch=jdbc.queryForObject("SELECT stream_epoch FROM recovery_user_stream",String.class);
        var result=bootstrap().initializeUnderMaintenance();
        assertEquals(2,result.messagesInserted());assertEquals(4,result.accessInserted());
        assertEquals("RECALLED",jdbc.queryForObject("SELECT state FROM recovery_message_state WHERE message_id='old-recall'",String.class));
        assertEquals(2L,jdbc.queryForObject("SELECT object_version FROM recovery_message_state WHERE message_id='old-recall'",Long.class));
        assertEquals(0,bootstrap().initializeUnderMaintenance().messagesInserted());
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
        assertEquals(epoch,jdbc.queryForObject("SELECT stream_epoch FROM recovery_user_stream",String.class));
        assertEquals(1L,jdbc.queryForObject("SELECT latest_cursor FROM recovery_user_stream",Long.class));
    }

    @Test
    void bootstrapInvalidLegacyRowRollsBackAndExistingMetadataIsNeverOverwritten() {
        seedTerminalMessage();
        jdbc.update("INSERT INTO chat_message(message_id,conversation_id,sequence,is_recalled,status) VALUES ('zz-invalid','group:21',NULL,0,0)");
        assertThrows(IllegalStateException.class,()->bootstrap().initializeUnderMaintenance());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_message_state",Long.class));
        jdbc.update("DELETE FROM chat_message WHERE message_id='zz-invalid'");
        bootstrap().initializeUnderMaintenance();
        jdbc.update("UPDATE recovery_message_state SET state='UNAVAILABLE',object_version=9 WHERE message_id='terminal'");
        assertThrows(IllegalStateException.class,()->bootstrap().initializeUnderMaintenance());
        assertEquals("UNAVAILABLE",jdbc.queryForObject("SELECT state FROM recovery_message_state",String.class));
        assertEquals(9L,jdbc.queryForObject("SELECT object_version FROM recovery_message_state",Long.class));
    }

    @Test
    void newReliableMessageHasVersionOneBeforeAnyTerminalTransitionAndRetryDoesNotRecreateIt() {
        seedTerminalMessage();
        var service=groupSendingService(realGroupConversationService());
        var sent=service.saveReliableMessage(newGroupMessage(),"group:21").message();
        assertEquals(1L,jdbc.queryForObject("SELECT object_version FROM recovery_message_state WHERE message_id=?",Long.class,sent.getMessageId()));
        assertEquals("NORMAL",jdbc.queryForObject("SELECT state FROM recovery_message_state WHERE message_id=?",String.class,sent.getMessageId()));
        assertTrue(service.saveReliableMessage(newGroupMessage(),"group:21").duplicated());
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_message_state",Long.class));
    }

    @Test
    void newPrivateDirectoryPublishesRebuildForBothUsersOnlyOnceEvenWhenSendDenied() {
        var conversations=realGroupConversationService();
        tx.executeWithoutResult(status -> conversations.ensurePrivateConversation(9L,7L));
        tx.executeWithoutResult(status -> conversations.ensurePrivateConversation(7L,9L));
        assertEquals(List.of(7L,9L),jdbc.queryForList("SELECT user_id FROM recovery_mutation ORDER BY user_id",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation WHERE reason='GRANTED' AND rebuild_conversation=TRUE AND read_allowed=TRUE AND send_allowed=FALSE",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member",Long.class));
    }

    private com.lanchat.service.MessageAttachmentCleanup attachmentCleanup() {
        return new com.lanchat.service.MessageAttachmentCleanup(sqlSession.getMapper(com.lanchat.mapper.FileMetadataMapper.class),
                sqlSession.getMapper(com.lanchat.mapper.FileAccessGrantMapper.class),cleanupFiles);
    }

    private void seedAttachment() {
        seedTerminalMessage();
        jdbc.update("UPDATE chat_message SET group_id=21,type='file',file_path='0123456789abcdef0123456789abcdef.pdf',content='/api/v1/file/content/0123456789abcdef0123456789abcdef.pdf'");
        jdbc.update("INSERT INTO file_metadata(id,file_path,upload_user_id) VALUES (1,'0123456789abcdef0123456789abcdef.pdf',7)");
        jdbc.update("INSERT INTO file_access_grant(file_id,user_id,grant_type) VALUES (1,7,'UPLOADER')");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"FOREIGN","TEXT_URL","USER_AVATAR","GROUP_AVATAR","GRANT","BROADCAST","TRANSFER","UPLOAD_SESSION","OTHER_MESSAGE"})
    void recallPreservesFilesWithIndependentOwnershipOrReferences(String protection) {
        seedAttachment();
        String url="/api/v1/file/content/0123456789abcdef0123456789abcdef.pdf";
        switch(protection) {
            case "FOREIGN" -> jdbc.update("UPDATE file_metadata SET upload_user_id=99");
            case "TEXT_URL" -> jdbc.update("UPDATE chat_message SET file_path=NULL,type='text'");
            case "USER_AVATAR" -> jdbc.update("INSERT INTO `user` VALUES (7,?)",url);
            case "GROUP_AVATAR" -> jdbc.update("UPDATE chat_group SET avatar=?",url);
            case "GRANT" -> jdbc.update("INSERT INTO file_access_grant(file_id,user_id,grant_type) VALUES (1,9,'UPLOAD_PROOF')");
            case "BROADCAST" -> jdbc.update("INSERT INTO broadcast_evidence VALUES (1)");
            case "TRANSFER" -> jdbc.update("INSERT INTO file_transfer VALUES (1)");
            case "UPLOAD_SESSION" -> jdbc.update("INSERT INTO file_upload_session VALUES (1)");
            case "OTHER_MESSAGE" -> jdbc.update("INSERT INTO chat_message(message_id,conversation_id,sequence,is_recalled,status,file_path) VALUES ('other','group:21',61,0,0,'0123456789abcdef0123456789abcdef.pdf')");
        }
        assertTrue(terminalService(true).recallMessage("terminal",7L));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata",Long.class));
        verifyNoInteractions(cleanupFiles);
        assertEquals(1,jdbc.queryForObject("SELECT is_recalled FROM chat_message WHERE message_id='terminal'",Integer.class));
    }

    @Test
    void recallCleanupIntentFailureRollsBackMetadataBodyVersionAndFacts() {
        seedAttachment();
        doThrow(new IllegalStateException("synthetic cleanup intent failure")).when(cleanupFiles).deleteStoredObjects(any());
        assertThrows(IllegalStateException.class,()->terminalService(true).recallMessage("terminal",7L));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM file_access_grant",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT is_recalled FROM chat_message",Integer.class));
        assertFalse(jdbc.queryForObject("SELECT content FROM chat_message",String.class).isEmpty());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_message_state",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM message_recall",Long.class));
    }

    @Test
    void groupDissolutionCleansOnlyUnreferencedOwnedAttachments() {
        seedAttachment();
        assertTrue(groupRemovalService(realGroupConversationService()).dissolveGroup(21L,9L));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM file_access_grant",Long.class));
        verify(cleanupFiles).deleteStoredObjects(any());
        assertEquals("UNAVAILABLE",jdbc.queryForObject("SELECT state FROM recovery_message_state",String.class));
    }

    @Test
    void burnCleansUnsharedOwnedAttachmentWithoutKeepingItsTerminalReferenceAlive() {
        seedAttachment();
        terminalService(true).markAsBurned("terminal",9L);
        assertEquals(2,jdbc.queryForObject("SELECT status FROM chat_message",Integer.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM file_access_grant",Long.class));
        verify(cleanupFiles).deleteStoredObjects(any());
        assertEquals("BURNED",jdbc.queryForObject("SELECT state FROM recovery_message_state",String.class));
    }

    private ConversationService realGroupConversationService() {
        var service = new com.lanchat.service.impl.ConversationServiceImpl(
                sqlSession.getMapper(com.lanchat.mapper.ConversationMapper.class),
                sqlSession.getMapper(com.lanchat.mapper.ConversationMemberMapper.class),
                sqlSession.getMapper(ChatMessageMapper.class),
                sqlSession.getMapper(com.lanchat.mapper.GroupMemberMapper.class),
                mock(com.lanchat.mapper.TemporaryRoomMapper.class), mock(com.lanchat.service.FriendService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service, "writeGuard", new ConversationWriteGuard(jdbc));
        ReflectionTestUtils.setField(service, "accessMutationRecorder",new AccessMutationRecorder(jdbc,journal,new ConversationWriteGuard(jdbc),true));
        return service;
    }

    private com.lanchat.service.GroupService groupRemovalService(ConversationService conversations) {
        var service = new com.lanchat.service.impl.GroupServiceImpl();
        ReflectionTestUtils.setField(service, "memberMapper", sqlSession.getMapper(com.lanchat.mapper.GroupMemberMapper.class));
        ReflectionTestUtils.setField(service, "groupMapper", sqlSession.getMapper(com.lanchat.mapper.ChatGroupMapper.class));
        ReflectionTestUtils.setField(service, "chatMessageMapper", sqlSession.getMapper(ChatMessageMapper.class));
        ReflectionTestUtils.setField(service, "messageRecallMapper", sqlSession.getMapper(MessageRecallMapper.class));
        ReflectionTestUtils.setField(service, "messageMutationRecorder", new MessageMutationRecorder(jdbc, journal, true));
        ReflectionTestUtils.setField(service, "attachmentCleanup",attachmentCleanup());
        var users = mock(com.lanchat.mapper.UserMapper.class);
        when(users.selectById(anyLong())).thenAnswer(call -> {
            var user = new com.lanchat.entity.User();
            user.setId(call.getArgument(0)); user.setStatus(1); return user;
        });
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "conversationService", conversations);
        ReflectionTestUtils.setField(service, "accessMutationRecorder",
                new AccessMutationRecorder(jdbc, journal, new ConversationWriteGuard(jdbc), true));
        return (com.lanchat.service.GroupService) transactionalProxy(service);
    }

    private ChatMessageService groupSendingService(ConversationService conversations) {
        var service = new ChatMessageServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", sqlSession.getMapper(ChatMessageMapper.class));
        ReflectionTestUtils.setField(service, "conversationService", conversations);
        ReflectionTestUtils.setField(service, "messageMutationRecorder",new MessageMutationRecorder(jdbc,journal,true));
        return (ChatMessageService) transactionalProxy(service);
    }

    private Object transactionalProxy(Object target) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return proxy.getProxy();
    }

    private com.lanchat.entity.ChatMessage newGroupMessage() {
        var message = new com.lanchat.entity.ChatMessage();
        message.setFromUserId(7L);
        message.setGroupId(21L);
        message.setClientMsgId("group-send-after-barrier");
        message.setType("text");
        message.setContent("synthetic group write");
        return message;
    }

    @Test
    void removedUserCannotSendEvenInsideAnOlderRepeatableReadView() throws Exception {
        seedTerminalMessage();
        var conversations = realGroupConversationService();
        var removal = groupRemovalService(conversations);
        var sending = groupSendingService(conversations);
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.execute(status -> {
                assertEquals(2, count("group_member")); // intentionally establish a stale MVCC view
                assertTrue(assertDoesNotThrow(() -> executor.submit(() -> removal.removeMember(21L, 9L, 7L))
                        .get(10, TimeUnit.SECONDS)));
                assertThrows(IllegalArgumentException.class, () -> sending.saveReliableMessage(newGroupMessage(), "group:21"));
                status.setRollbackOnly();
                return null;
            });
            assertEquals(1, count("chat_message"));
            assertEquals(62L, jdbc.queryForObject("SELECT last_sequence FROM conversation WHERE id='group:21'", Long.class));
            assertEquals(List.of(9L), jdbc.queryForList("SELECT user_id FROM group_member", Long.class));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void removalWaitsForEarlierAuthorizedSendToCommit() throws Exception {
        seedTerminalMessage();
        var conversations = realGroupConversationService();
        var removal = groupRemovalService(conversations);
        var sending = groupSendingService(conversations);
        var executor = Executors.newSingleThreadExecutor();
        var removalStarted = new CountDownLatch(1);
        var pending = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
        try {
            tx.execute(status -> {
                var sent = sending.saveReliableMessage(newGroupMessage(), "group:21");
                assertEquals(63L, sent.message().getSequence());
                pending.set(executor.submit(() -> {
                    removalStarted.countDown();
                    return removal.removeMember(21L, 9L, 7L);
                }));
                assertTrue(assertDoesNotThrow(() -> removalStarted.await(5, TimeUnit.SECONDS)));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> pending.get().get(150, TimeUnit.MILLISECONDS));
                return null;
            });
            assertTrue(pending.get().get(10, TimeUnit.SECONDS));
            assertEquals(2, count("chat_message"));
            assertEquals(List.of(9L), jdbc.queryForList("SELECT user_id FROM group_member", Long.class));
            assertThrows(IllegalArgumentException.class, () -> sending.saveReliableMessage(newGroupMessage(), "group:21"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

}
