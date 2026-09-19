package com.lanchat.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Sanitized, bounded view of the active runtime log for the administrator console. */
public record RuntimeLogSnapshot(
        boolean available,
        String fileName,
        long fileSizeBytes,
        Instant updatedAt,
        int scannedEntries,
        boolean truncated,
        Map<String, Long> levelCounts,
        List<RuntimeLogEntry> entries,
        String notice
) {
    public RuntimeLogSnapshot {
        levelCounts = Map.copyOf(levelCounts);
        entries = List.copyOf(entries);
    }

    public record RuntimeLogEntry(
            long sequence,
            String timestamp,
            String level,
            String thread,
            String requestId,
            String logger,
            String message,
            String details,
            String explanation
    ) {
    }
}
