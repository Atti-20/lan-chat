package com.lanchat.dto;

import com.lanchat.entity.Broadcast;

import java.util.List;

/** Snapshot used to notify the former audience after a broadcast is deleted. */
public record BroadcastDeleteResult(Broadcast broadcast, List<Long> receiverIds) {
    public BroadcastDeleteResult {
        receiverIds = receiverIds == null ? List.of() : List.copyOf(receiverIds);
    }
}
