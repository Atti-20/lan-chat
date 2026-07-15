package com.lanchat.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Detailed diagnostics visible only to the node administrator. */
public record AdminDiagnostics(
        String nodeId,
        String nodeName,
        String mode,
        String version,
        Instant startedAt,
        long uptimeSeconds,
        int onlineUsers,
        int webSocketConnections,
        long webSocketEvents,
        long chatAcknowledgements,
        long webSocketFailures,
        double averageEventProcessingMs,
        DependencyStatus database,
        DependencyStatus redis,
        StorageStatus storage,
        JvmStatus jvm,
        List<Map<String, Object>> recentConnections,
        List<String> warnings
) {
    public record DependencyStatus(String status, Long latencyMs, String message) {
    }

    public record StorageStatus(
            String status,
            String path,
            long totalBytes,
            long usableBytes,
            long usedBytes,
            double usedPercent,
            String message
    ) {
    }

    public record JvmStatus(
            long heapUsedBytes,
            long heapMaxBytes,
            int threadCount,
            int availableProcessors,
            double systemLoadAverage
    ) {
    }
}
