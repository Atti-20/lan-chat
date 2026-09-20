package com.lanchat.recovery;

/** Stable recovery failure; deliberately contains no account or message content. */
public class RecoveryFault extends RuntimeException {
    private final int status;
    private final String reason;
    public RecoveryFault(int status,String reason) { super(reason);this.status=status;this.reason=reason; }
    public int status() { return status; }
    public String reason() { return reason; }
}
