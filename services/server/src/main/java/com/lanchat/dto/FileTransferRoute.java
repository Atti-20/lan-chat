package com.lanchat.dto;

/** A strictly authorized point-to-point signaling route. */
public record FileTransferRoute(
        String transferId,
        String conversationId,
        Long targetUserId,
        Long targetDeviceId,
        String status
) {
}
