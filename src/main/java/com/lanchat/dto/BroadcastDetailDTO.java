package com.lanchat.dto;

import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;

import java.util.List;

/** Broadcast plus the current user's receiver state, when the user is a target. */
public record BroadcastDetailDTO(
        Broadcast broadcast,
        BroadcastReceiver receiver,
        BroadcastSenderDTO sender,
        BroadcastContentEvidenceDTO contentEvidence,
        List<String> confirmationOptions,
        boolean createdByCurrentUser
) {
    /** Compatibility constructor for existing internal callers that predate sender data. */
    public BroadcastDetailDTO(Broadcast broadcast,
                              BroadcastReceiver receiver,
                              List<String> confirmationOptions,
                              boolean createdByCurrentUser) {
        this(broadcast, receiver, null, null, confirmationOptions, createdByCurrentUser);
    }
}
