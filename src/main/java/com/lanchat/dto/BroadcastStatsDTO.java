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
        long executedCount,
        long needSupportCount,
        long removedCount,
        List<Long> unconfirmedUserIds,
        long expiredCount,
        boolean expired,
        Map<String, Long> confirmationCounts
) {
    public BroadcastStatsDTO(Long broadcastId, long targetCount, long deliveredCount,
                             long viewedCount, long confirmedCount, long unconfirmedCount,
                             List<Long> unconfirmedUserIds, long expiredCount, boolean expired,
                             Map<String, Long> confirmationCounts) {
        this(broadcastId, targetCount, deliveredCount, viewedCount, confirmedCount,
                unconfirmedCount, 0, 0, 0, unconfirmedUserIds, expiredCount,
                expired, confirmationCounts);
    }
}
