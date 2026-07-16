package com.lanchat.entity;

/** Persistent lifecycle states for a peer-to-peer file transfer attempt. */
public enum FileTransferStatus {
    OFFERED,
    CLAIMED,
    NEGOTIATING,
    TRANSFERRING,
    P2P_COMPLETED,
    RELAY_PENDING,
    RELAY_COMPLETED,
    FAILED,
    EXPIRED;

    public boolean isActive() {
        return this == OFFERED
                || this == CLAIMED
                || this == NEGOTIATING
                || this == TRANSFERRING
                || this == RELAY_PENDING;
    }

    public boolean isPeerActive() {
        return this == CLAIMED || this == NEGOTIATING || this == TRANSFERRING;
    }

    public boolean isCompleted() {
        return this == P2P_COMPLETED || this == RELAY_COMPLETED;
    }

    public static FileTransferStatus fromStoredValue(String value) {
        try {
            return FileTransferStatus.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("文件传输状态无效");
        }
    }
}
