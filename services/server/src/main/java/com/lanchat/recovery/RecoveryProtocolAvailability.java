package com.lanchat.recovery;

/** One advertising gate for REST and WS. Close deployment, client and capacity gates before enabling. */
public final class RecoveryProtocolAvailability {
    private RecoveryProtocolAvailability() {}
    public static boolean isAdvertised() {return false;}
}
