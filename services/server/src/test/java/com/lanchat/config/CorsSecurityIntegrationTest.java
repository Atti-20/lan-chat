package com.lanchat.config;

import com.lanchat.service.BroadcastNotificationAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "tunnel.enabled=false",
        "jwt.secret=test-only-signing-key-for-spring-context-tests"
})
@AutoConfigureMockMvc
class CorsSecurityIntegrationTest {

    // CORS assertions do not exercise account provisioning. Override the
    // startup runner so this MVC-only context remains independent of MySQL.
    @MockitoBean
    private BroadcastNotificationAccountService broadcastNotificationAccountService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void controlHandshakeIsPublicAndUsesTheV2Contract() throws Exception {
        mockMvc.perform(get("/api/v2/control/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.controlId").isString())
                .andExpect(jsonPath("$.data.organizationId").value("org-local"))
                .andExpect(jsonPath("$.data.protocolVersion").value(2))
                .andExpect(jsonPath("$.data.healthPath")
                        .value("/api/v2/control/health"));

        mockMvc.perform(get("/api/v2/control/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.protocolVersion").value(2));
    }

    @Test
    void controlRbacAuditDeviceAndSessionEndpointsAreNeverPublic() throws Exception {
        mockMvc.perform(get("/api/v2/control/rbac/roles"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/v2/control/audit"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/v2/control/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/v2/control/devices/revocations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/v2/control/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/v2/control/policy"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void allowedDesktopPreflightPassesBeforeAuthenticationWithoutCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/user/info")
                        .header(HttpHeaders.ORIGIN, "tauri://localhost")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "authorization,content-type,x-request-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "tauri://localhost"))
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void allowedCapacitorPreflightPassesBeforeAuthenticationWithoutCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/node/info")
                        .header(HttpHeaders.ORIGIN, "https://localhost")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "authorization,content-type,x-request-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://localhost"))
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void unlistedOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/node/info")
                        .header(HttpHeaders.ORIGIN, "https://malicious.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
