package com.lanchat.service;

import com.lanchat.dto.RuntimeLogSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLogServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void parsesLevelsStackTracesAndDatabaseExplanation() throws Exception {
        Path logFile = tempDirectory.resolve("lan-chat.log");
        Files.writeString(logFile, """
                2026-07-15T19:00:00.100+08:00 INFO  [main] [requestId=system] com.lanchat.LanChatApplication - Started LanChatApplication
                2026-07-15T19:00:01.200+08:00 WARN  [main] [requestId=system] com.lanchat.service.NodeDiagnosticsService - Redis connection timed out
                2026-07-15T19:00:02.300+08:00 ERROR [http-nio-8080-exec-1] [requestId=req_abc123] com.lanchat.common.GlobalExceptionHandler - Communications link failure
                java.sql.SQLException: Communications link failure
                \tat com.mysql.cj.jdbc.ConnectionImpl.connectOneTryOnly(ConnectionImpl.java:1)
                """, StandardCharsets.UTF_8);
        RuntimeLogService service = new RuntimeLogService(logFile.toString(), 1024 * 1024);

        RuntimeLogSnapshot snapshot = service.read(300, "ALL", "");

        assertTrue(snapshot.available());
        assertEquals(3, snapshot.entries().size());
        assertEquals(1, snapshot.levelCounts().get("ERROR"));
        assertEquals(1, snapshot.levelCounts().get("WARN"));
        assertEquals("ERROR", snapshot.entries().get(0).level());
        assertTrue(snapshot.entries().get(0).details().contains("java.sql.SQLException"));
        assertTrue(snapshot.entries().get(0).explanation().contains("数据库"));
        assertTrue(snapshot.entries().get(1).explanation().contains("Redis"));
    }

    @Test
    void filtersByLevelKeywordAndRejectsUnknownLevel() throws Exception {
        Path logFile = tempDirectory.resolve("filter.log");
        Files.writeString(logFile, """
                2026-07-15T19:00:00.100+08:00 INFO  [main] [requestId=system] com.lanchat.App - Node started
                2026-07-15T19:00:02.300+08:00 ERROR [worker] [requestId=req_filter1] com.lanchat.Worker - WebSocket handshake failed
                """, StandardCharsets.UTF_8);
        RuntimeLogService service = new RuntimeLogService(logFile.toString(), 1024 * 1024);

        RuntimeLogSnapshot snapshot = service.read(100, "ERROR", "req_filter1");

        assertEquals(1, snapshot.entries().size());
        assertEquals("req_filter1", snapshot.entries().get(0).requestId());
        assertThrows(IllegalArgumentException.class, () -> service.read(100, "FATAL", ""));
    }

    @Test
    void reportsMissingLogAndOnlyExportsConfiguredFile() throws Exception {
        Path missing = tempDirectory.resolve("missing.log");
        RuntimeLogService service = new RuntimeLogService(missing.toString(), 1024 * 1024);

        assertFalse(service.read(100, "ALL", "").available());
        assertTrue(service.openExport().isEmpty());

        Files.writeString(missing, "runtime log", StandardCharsets.UTF_8);
        var exported = service.openExport().orElseThrow();
        assertEquals("runtime log", exported.resource().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(exported.fileName().startsWith("lan-chat-runtime-"));
    }
}
