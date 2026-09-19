package com.lanchat.cluster;

import com.lanchat.dto.WebSocketEnvelope;

/** Delivers only to sessions held by the current Spring Boot process. */
public interface LocalRealtimeDelivery {

    void sendToUser(Long userId, WebSocketEnvelope event);

    /** Returns the number of local WebSocket sessions that accepted the frame. */
    default int sendToUserWithReceipt(Long userId, WebSocketEnvelope event) {
        sendToUser(userId, event);
        return 0;
    }

    void sendToDevice(Long userId, Long deviceId, WebSocketEnvelope event);

    void broadcast(WebSocketEnvelope event);
}
