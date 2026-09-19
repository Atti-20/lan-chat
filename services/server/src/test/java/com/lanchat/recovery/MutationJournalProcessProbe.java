package com.lanchat.recovery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.LongStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent JVM using only the explicitly owned test database; not an application server. */
public final class MutationJournalProcessProbe {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("MESHX_RECOVERY_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mx_recovery_test[?].*")) {
            throw new IllegalStateException("Owned fixture required");
        }
        var source = new DriverManagerDataSource(url, "root", System.getenv("MESHX_RECOVERY_TEST_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        if (!"mx_recovery_test".equals(jdbc.queryForObject("SELECT DATABASE()", String.class))) {
            throw new IllegalStateException("Wrong fixture database");
        }
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(60);
        var journal = new MutationJournal(jdbc);
        var recipients = LongStream.rangeClosed(7, 506).boxed().toList();
        Path directory = Path.of(args[0]);
        String worker = args[1];
        Files.writeString(directory.resolve(worker + ".ready"), "ready");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(directory.resolve("go"))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Start barrier timed out");
            Thread.sleep(10);
        }
        tx.execute(status -> journal.append(UUID.fromString(args[2]), recipients,
                MutationFact.message(MutationFact.Type.MESSAGE_RECALLED, "group:21", "shared-message", 2)));
        for (int index = 0; index < 3; index++) {
            String message = worker + "-message-" + index;
            tx.execute(status -> journal.append(UUID.nameUUIDFromBytes(message.getBytes(StandardCharsets.UTF_8)),
                    recipients, MutationFact.message(MutationFact.Type.MESSAGE_RECALLED, "group:21", message, 2)));
        }
        System.out.println("PASS worker=" + worker + " recipients=500");
    }
}
