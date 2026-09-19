package com.lanchat.cluster;

/** Redis-only acknowledgement that a routed frame reached a concrete WebSocket session. */
public record RealtimeDeliveryAck(
        int version,
        String ackEventId,
        String clusterId,
        String targetInstanceId,
        long createdAt
) {
}
