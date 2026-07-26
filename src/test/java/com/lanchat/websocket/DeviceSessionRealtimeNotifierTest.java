package com.lanchat.websocket;

import com.lanchat.common.DeviceSessionsRevokedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeviceSessionRealtimeNotifierTest {

    @Test
    void committedRevocationDelegatesToWebSocketHandler() throws Exception {
        ChatWebSocketHandler handler = mock(ChatWebSocketHandler.class);
        DeviceSessionRealtimeNotifier notifier = new DeviceSessionRealtimeNotifier(handler);
        DeviceSessionsRevokedEvent event = new DeviceSessionsRevokedEvent(
                7L, List.of(31L), "PASSWORD_CHANGED", "密码已修改，请重新登录");

        notifier.onSessionsRevoked(event);

        verify(handler).forceLogoutDevices(event);
        TransactionalEventListener listener = DeviceSessionRealtimeNotifier.class
                .getMethod("onSessionsRevoked", DeviceSessionsRevokedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertNotNull(listener);
        assertEquals(TransactionPhase.AFTER_COMMIT, listener.phase());
        assertTrue(listener.fallbackExecution());
    }
}
