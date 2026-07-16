package com.lanchat.cluster;

import com.lanchat.dto.WebSocketEnvelope;

import java.util.List;

/** JSON-safe Redis event. It never carries access or refresh tokens. */
public record RealtimeRouteEvent(
        int version,
        String eventId,
        String clusterId,
        String originInstanceId,
        RealtimeRouteScope scope,
        List<Long> targetUserIds,
        Long targetDeviceId,
        long createdAt,
        boolean deliveryReceiptRequested,
        WebSocketEnvelope envelope
) {
}
