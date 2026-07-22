package com.lanchat.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsConfigTest {

    @Test
    void defaultPolicyAllowsExactWebViewOriginsWithoutCredentials() {
        CorsConfig config = new CorsConfig(ClientOriginDefaults.ALLOWED_ORIGINS.split(","));
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        CorsConfiguration policy = source.getCorsConfiguration(
                new MockHttpServletRequest("OPTIONS", "/api/v1/node/info"));

        assertTrue(policy.getAllowedOrigins().containsAll(List.of(
                "tauri://localhost",
                "http://tauri.localhost",
                "https://tauri.localhost",
                "http://127.0.0.1:1420",
                "http://localhost:1420",
                "https://localhost"
        )));
        assertEquals(Boolean.FALSE, policy.getAllowCredentials());
        assertEquals(List.of("Authorization", "Content-Type", "X-Request-ID"),
                policy.getAllowedHeaders());
        assertEquals("tauri://localhost", policy.checkOrigin("tauri://localhost"));
        assertEquals("https://localhost", policy.checkOrigin("https://localhost"));
        assertNull(policy.checkOrigin("https://malicious.example"));
        assertFalse(policy.getAllowedOrigins().contains("*"));
    }

    @Test
    void wildcardOriginIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CorsConfig(new String[]{"*"}));
    }
}
