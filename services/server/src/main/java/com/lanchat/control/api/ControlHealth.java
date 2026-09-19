package com.lanchat.control.api;

/** Lightweight control-plane liveness response without dependency details. */
public record ControlHealth(
        String status,
        String controlId,
        int protocolVersion,
        String version,
        long uptimeSeconds,
        long serverTime
) {
}
