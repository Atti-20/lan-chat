package com.lanchat.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static com.lanchat.recovery.MutationJournalMySqlIT.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real independent JVM services and MySQL locks; HTTP/auth/WS are outside this fixture. */
class RecoveryProcessesMySqlIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String ORIGIN = "https://meshx.test";

    @Test
    void sharedSessionPagesWaitForForeignRecallAndReadyRequiresTheNewCut() throws Exception {
        database();
        var fixture = new MutationJournalMySqlIT();
        fixture.cleanOwnedFixture();
        Path directory = Files.createTempDirectory("meshx-recovery-processes-");
        try (var first = new Node(directory, "first"); var second = new Node(directory, "second")) {
            fixture.manifestConversation("group:21", 405);
            String id = first.call(Map.of("action", "open")).get("recoveryId").asText();
            var initial = first.call(Map.of("action", "page", "id", id));
            assertEquals(100, initial.get("items").size());
            assertEquals("NORMAL", initial.get("items").get(1).get("state").asText());
            String token = initial.get("nextPageToken").asText();

            // Hold the actual business transaction in the other JVM. Observe an actual
            // InnoDB wait before releasing it, rather than assuming a sleep proves overlap.
            Path recalled = second.send(Map.of("action", "recall", "message", "group:21-1", "hold", true));
            awaitFile(directory.resolve("writer.locked"), second.process);
            Path blockedPage = first.send(Map.of("action", "page", "id", id));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits", Integer.class) == 0) {
                assertTrue(first.process.isAlive() && second.process.isAlive());
                assertFalse(Files.exists(blockedPage), "Page must wait for the foreign conversation lock");
                assertTrue(System.nanoTime() < deadline, "No cross-process InnoDB wait observed");
                Thread.sleep(20);
            }
            Files.writeString(directory.resolve("writer.release"), "release");
            assertTrue(second.result(recalled).asBoolean());
            var retry = first.result(blockedPage);
            assertEquals(token, retry.get("nextPageToken").asText());
            var terminal = retry.get("items").get(1);
            assertEquals("RECALLED", terminal.get("state").asText());
            assertEquals("2", terminal.get("objectVersion").asText());
            assertFalse(terminal.hasNonNull("content"));
            assertFalse(terminal.hasNonNull("details"));

            int count = 100, pageNumber = 1;
            while (token != null) {
                var page = (pageNumber++ % 2 == 0 ? first : second).call(Map.of("action", "page", "id", id, "token", token));
                count += page.get("items").size();
                token = page.hasNonNull("nextPageToken") ? page.get("nextPageToken").asText() : null;
                assertEquals(token == null, page.get("snapshotComplete").asBoolean());
            }
            assertEquals(406, count);
            assertEquals("1", first.call(Map.of("action", "cut", "id", id)).get("through").asText());
            assertTrue(second.call(Map.of("action", "recall", "message", "group:21-405")).asBoolean());
            assertEquals("1", second.call(Map.of("action", "cut", "id", id)).get("through").asText(), "Fixed cut must not drift on the other node");
            assertFalse(first.call(Map.of("action", "ready", "id", id, "cursor", "1")).get("ready").asBoolean());
            assertEquals("2", second.call(Map.of("action", "cut", "id", id)).get("through").asText());
            var changes = first.call(Map.of("action", "mutations", "id", id));
            assertEquals(2, changes.get("records").size());
            assertEquals("group:21-1", changes.get("records").get(0).get("messageId").asText());
            assertEquals("group:21-405", changes.get("records").get(1).get("messageId").asText());
            assertTrue(second.call(Map.of("action", "ready", "id", id, "cursor", "2")).get("ready").asBoolean());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_mutation", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_dispatch_outbox", Integer.class));
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (file.toString().endsWith(".log")) System.out.println(Files.readString(file));
                    Files.delete(file);
                }
            }
        }
    }

    private static void awaitFile(Path file, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!Files.exists(file)) {
            assertTrue(process.isAlive(), "Worker stopped before " + file.getFileName());
            assertTrue(System.nanoTime() < deadline, "Worker timed out: " + file.getFileName());
            Thread.sleep(20);
        }
    }

    private static final class Node implements AutoCloseable {
        final Process process;
        final PrintWriter input;
        final Path directory;
        final String name;
        int sequence;
        Node(Path directory, String name) throws Exception {
            this.directory = directory; this.name = name;
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    RecoveryProcessesMySqlIT.class.getName(), directory.toString(), name)
                    .redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile()).start();
            input = new PrintWriter(process.getOutputStream(), true);
            try { awaitFile(directory.resolve(name + ".ready"), process); }
            catch (Throwable failure) { close(); throw failure; }
        }
        Path send(Map<String, Object> command) throws Exception {
            var request = new java.util.HashMap<>(command);
            Path result = directory.resolve(name + "-" + (++sequence) + ".result");
            request.put("result", result.getFileName().toString());
            input.println(JSON.writeValueAsString(request));
            assertFalse(input.checkError());
            return result;
        }
        JsonNode result(Path result) throws Exception {
            awaitFile(result, process);
            var response = JSON.readTree(Files.readString(result));
            assertFalse(response.has("error"), response.toString());
            return response.get("value");
        }
        JsonNode call(Map<String, Object> command) throws Exception { return result(send(command)); }
        @Override public void close() throws Exception {
            input.close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        database(); // Idempotent owned-fixture DDL; never clears or seeds shared data.
        var fixture = new MutationJournalMySqlIT();
        Path directory = Path.of(args[0]);
        Files.writeString(directory.resolve(args[1] + ".ready"), "ready");
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = input.readLine()) != null;) {
                var request = JSON.readTree(line);
                Object value;
                try {
                    String id = request.path("id").asText();
                    value = switch (request.get("action").asText()) {
                        case "open" -> fixture.manifests().create(7, ORIGIN);
                        case "page" -> fixture.snapshotPages().page(7, ORIGIN, id,
                                request.has("token") ? request.get("token").asText() : null, 100);
                        case "cut" -> fixture.resumeSessions().cut(7, ORIGIN, id);
                        case "ready" -> fixture.resumeSessions().ready(7, ORIGIN, id, request.get("cursor").asText(), true);
                        case "mutations" -> fixture.resumeSessions().mutations(7, ORIGIN, id, "0", "2", 100);
                        case "recall" -> tx.execute(status -> {
                            new ConversationWriteGuard(jdbc).lock("group:21");
                            if (request.path("hold").asBoolean()) {
                                try {
                                    Files.writeString(directory.resolve("writer.locked"), "locked");
                                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                                    while (!Files.exists(directory.resolve("writer.release"))) {
                                        if (System.nanoTime() > deadline) throw new IllegalStateException("Release barrier timed out");
                                        Thread.sleep(10);
                                    }
                                } catch (Exception failure) { throw new IllegalStateException(failure); }
                            }
                            return fixture.terminalService(true).recallMessage(request.get("message").asText(), 7L);
                        });
                        default -> throw new IllegalArgumentException("Unknown action");
                    };
                    value = Map.of("value", value);
                } catch (Throwable failure) { value = Map.of("error", failure.toString()); }
                Path result = directory.resolve(request.get("result").asText());
                Path temporary = directory.resolve(result.getFileName() + ".tmp");
                Files.writeString(temporary, JSON.writeValueAsString(value));
                Files.move(temporary, result, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }
}
