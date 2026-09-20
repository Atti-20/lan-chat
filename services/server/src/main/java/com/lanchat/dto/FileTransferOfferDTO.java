package com.lanchat.dto;

/** Metadata submitted before WebRTC negotiation. The receiver is derived from conversationId. */
public record FileTransferOfferDTO(
        String clientTransferId,
        String conversationId,
        String fileName,
        Long fileSize,
        String fileType,
        String fileHash
) {
}
