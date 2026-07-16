package com.lanchat.dto;

/** Receiver-reported values used to verify a completed DataChannel transfer. */
public record FileTransferCompletionDTO(
        String fileHash,
        Long fileSize
) {
}
