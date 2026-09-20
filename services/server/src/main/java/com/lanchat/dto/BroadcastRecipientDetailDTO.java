package com.lanchat.dto;

import java.time.LocalDateTime;
import java.util.List;

public record BroadcastRecipientDetailDTO(
        Long receiverId,
        Long userId,
        String username,
        String nickname,
        String avatar,

        String targetStatus,
        String confirmStatus,

        LocalDateTime deliveredAt,
        LocalDateTime viewedAt,
        LocalDateTime completedAt,

        List<String> imageUrls,
        BroadcastLocationDTO location,

        int remindCount,
        LocalDateTime lastRemindedAt
) {
}
