package com.lanchat.websocket;

import com.lanchat.common.TemporaryRoomChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Publishes room changes only after their database transaction has committed. */
@Component
public class TemporaryRoomRealtimeNotifier {

    private final ChatWebSocketHandler webSocketHandler;

    public TemporaryRoomRealtimeNotifier(ChatWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRoomChanged(TemporaryRoomChangedEvent event) {
        webSocketHandler.notifyTemporaryRoomChanged(event);
    }
}
