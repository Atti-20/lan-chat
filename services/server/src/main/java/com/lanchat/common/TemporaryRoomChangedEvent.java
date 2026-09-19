package com.lanchat.common;

import java.util.List;

/** Committed room membership or lifecycle change that must refresh connected clients. */
public record TemporaryRoomChangedEvent(
        Long roomId,
        String conversationId,
        String status,
        List<Long> memberIds
) {
    public TemporaryRoomChangedEvent {
        memberIds = memberIds == null ? List.of() : List.copyOf(memberIds);
    }
}
