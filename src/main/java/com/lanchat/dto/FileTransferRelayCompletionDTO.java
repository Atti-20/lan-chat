package com.lanchat.dto;

/** Authoritative node-storage result used to complete a relay fallback. */
public record FileTransferRelayCompletionDTO(
        Long fileMetadataId,
        String storedFileName,
        String fileHash,
        Long fileSize
) {
}
