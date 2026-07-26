package com.lanchat.websocket;

import com.lanchat.common.DeviceSessionsRevokedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Delivers session revocation only after the database transaction has committed. */
@Component
public class DeviceSessionRealtimeNotifier {

    private final ChatWebSocketHandler webSocketHandler;

    public DeviceSessionRealtimeNotifier(ChatWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSessionsRevoked(DeviceSessionsRevokedEvent event) {
        webSocketHandler.forceLogoutDevices(event);
    }
}
