package com.lanchat.cluster;

import com.lanchat.dto.WebSocketEnvelope;

/** Transparent local-first realtime router. */
public interface RealtimeRouter {

    void bind(LocalRealtimeDelivery delivery);

    void sendToUser(Long userId, WebSocketEnvelope event);

    /**
     * Routes an event and invokes {@code onDelivered} at most once, only after one
     * concrete WebSocket session accepted the frame on the instance that owns it.
     *
     * @return {@code false} when routing failed before either local handling or
     * cluster publication. A {@code true} result also covers an offline target:
     * the caller may safely rely on its durable source of truth for later sync.
     */
    boolean sendToUserWithReceipt(Long userId,
                                  WebSocketEnvelope event,
                                  Runnable onDelivered);

    void sendToDevice(Long userId, Long deviceId, WebSocketEnvelope event);

    void broadcast(WebSocketEnvelope event);
}
