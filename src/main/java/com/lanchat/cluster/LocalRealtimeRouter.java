package com.lanchat.cluster;

import com.lanchat.dto.WebSocketEnvelope;

/** Local-only router used by direct unit-test construction and cluster-disabled fallback. */
public final class LocalRealtimeRouter implements RealtimeRouter {

    private volatile LocalRealtimeDelivery delivery;

    @Override
    public void bind(LocalRealtimeDelivery delivery) {
        this.delivery = delivery;
    }

    @Override
    public void sendToUser(Long userId, WebSocketEnvelope event) {
        LocalRealtimeDelivery target = delivery;
        if (target != null) target.sendToUser(userId, event);
    }

    @Override
    public void sendToUserWithReceipt(Long userId,
                                      WebSocketEnvelope event,
                                      Runnable onDelivered) {
        LocalRealtimeDelivery target = delivery;
        if (target != null
                && target.sendToUserWithReceipt(userId, event) > 0
                && onDelivered != null) {
            onDelivered.run();
        }
    }

    @Override
    public void sendToDevice(Long userId, Long deviceId, WebSocketEnvelope event) {
        LocalRealtimeDelivery target = delivery;
        if (target != null) target.sendToDevice(userId, deviceId, event);
    }

    @Override
    public void broadcast(WebSocketEnvelope event) {
        LocalRealtimeDelivery target = delivery;
        if (target != null) target.broadcast(event);
    }
}
