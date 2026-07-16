package com.lanchat.cluster;

import com.lanchat.dto.WebSocketEnvelope;

/** Transparent local-first realtime router. */
public interface RealtimeRouter {

    void bind(LocalRealtimeDelivery delivery);

    void sendToUser(Long userId, WebSocketEnvelope event);

    /**
     * Routes an event and invokes {@code onDelivered} at most once, only after one
     * concrete WebSocket session accepted the frame on the instance that owns it.
     */
    void sendToUserWithReceipt(Long userId,
                               WebSocketEnvelope event,
                               Runnable onDelivered);

    void sendToDevice(Long userId, Long deviceId, WebSocketEnvelope event);

    void broadcast(WebSocketEnvelope event);
}
