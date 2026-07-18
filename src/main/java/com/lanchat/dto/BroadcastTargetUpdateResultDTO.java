package com.lanchat.dto;

import java.util.List;

public record BroadcastTargetUpdateResultDTO(
        List<Long> addedUserIds,
        List<Long> removedUserIds
) {
}
