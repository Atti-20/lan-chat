package com.lanchat.dto;

import java.util.List;
import java.util.Map;

/** Aggregated recipient progress visible to the sender and node administrator. */
public record BroadcastStatsDTO(
        Long broadcastId,
        long targetCount,
        long deliveredCount,
        long viewedCount,
        long confirmedCount,
        long unconfirmedCount,
        List<Long> unconfirmedUserIds,
        long expiredCount,
        boolean expired,
        Map<String, Long> confirmationCounts
) {
}
