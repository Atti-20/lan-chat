package com.lanchat.dto;

import java.time.LocalDateTime;

/** Sanitized transfer state returned to WebSocket and HTTP adapters. */
public record FileTransferVO(
        String transferId,
        String clientTransferId,
        String conversationId,
        Long senderUserId,
        Long senderDeviceId,
        Long receiverUserId,
        Long receiverDeviceId,
        String fileName,
        Long fileSize,
        String fileType,
        String fileHash,
        String status,
        String transportPath,
        Long fileMetadataId,
        String storedFileName,
        String fallbackReason,
        LocalDateTime expiresAt,
        LocalDateTime claimedTime,
        LocalDateTime completedTime,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
