package com.lanchat.websocket;

import com.lanchat.common.ConversationMembershipChangedEvent;
import com.lanchat.common.ConversationReadChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Delivers conversation state changes only after their database transaction commits. */
@Component
public class ConversationRealtimeNotifier {

    private final ChatWebSocketHandler webSocketHandler;

    public ConversationRealtimeNotifier(ChatWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReadChanged(ConversationReadChangedEvent event) {
        webSocketHandler.notifyConversationRead(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMembershipChanged(ConversationMembershipChangedEvent event) {
        webSocketHandler.notifyConversationMembershipChanged(event);
    }
}
