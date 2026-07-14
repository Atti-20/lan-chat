package com.lanchat.config;

import com.lanchat.security.WebSocketAuthInterceptor;
import com.lanchat.websocket.ChatWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    @Test
    void registersPublicAndLocalOrigins() {
        ChatWebSocketHandler handler = mock(ChatWebSocketHandler.class);
        WebSocketAuthInterceptor interceptor = mock(WebSocketAuthInterceptor.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        String[] allowedOrigins = {
                "http://localhost:8080",
                "http://127.0.0.1:8080",
                "https://chat.atti.cc.cd"
        };

        when(registry.addHandler(handler, "/ws/chat")).thenReturn(registration);
        when(registration.addInterceptors(interceptor)).thenReturn(registration);

        new WebSocketConfig(handler, interceptor, allowedOrigins)
                .registerWebSocketHandlers(registry);

        verify(registration).setAllowedOrigins(allowedOrigins);
    }
}
