package com.lanchat.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.common.GlobalExceptionHandler;
import com.lanchat.controller.AuthController;
import com.lanchat.controller.ChatController;
import com.lanchat.dto.*;
import com.lanchat.security.JwtAuthenticationFilter;
import com.lanchat.security.JwtUtil;
import com.lanchat.security.SecurityConfig;
import com.lanchat.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real loopback HTTP server + actual MVC/security/Jackson; no DB or startup runners. */
@SpringBootTest(classes = ContractHttpIntegrationTest.HttpContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=contract-http-test", "server.address=127.0.0.1",
                "jwt.refresh-expiration=604800000", "auth.refresh-cookie.secure=true"})
class ContractHttpIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class})
    @Import({AuthController.class, ChatController.class, SecurityConfig.class,
            JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
    static class HttpContext {}

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean UserService users;
    @MockitoBean JwtUtil jwt;
    @MockitoBean ChatMessageService messages;
    @MockitoBean ConversationService conversations;
    @MockitoBean GroupService groups;
    @MockitoBean org.springframework.security.core.userdetails.UserDetailsService details;

    JsonNode example(String id) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.isDirectory(root.resolve("contracts"))) root = root.getParent();
        for (var v : mapper.readTree(root.resolve("contracts/fixtures/core-v1.json").toFile()).get("rest")) {
            if (id.equals(v.path("id").asText())) return v.get("value");
        }
        throw new AssertionError(id);
    }
    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }
    HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, String json, String cookie) throws Exception {
        var request = request(path).header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        return send(request.POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    @Test void sharedLoginAndConversationExamplesMatchRealHttpAndCookieRefreshLogout() throws Exception {
        var loginJson = example("http-login-response");
        var login = mapper.treeToValue(loginJson.get("data"), LoginVO.class);
        login.setRefreshToken("fixture-refresh");
        when(users.login(any(LoginDTO.class))).thenReturn(login);
        var response = post("/api/v1/auth/login", example("login-normal").toString(), null);
        assertEquals(200, response.statusCode());
        assertEquals(loginJson, mapper.readTree(response.body()));
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        for (String part : List.of("lanchat_refresh=fixture-refresh", "HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth")) {
            assertTrue(cookie.contains(part), part);
        }
        assertFalse(response.body().contains("refreshToken"));
        assertFalse(response.body().contains("fixture-refresh"));
        var denied = send(request("/api/v1/chat/conversations").GET());
        assertEquals(401, denied.statusCode());
        assertEquals(401, mapper.readTree(denied.body()).path("code").asInt());

        when(jwt.isAccessToken("fixture-access")).thenReturn(true);
        when(jwt.getUserIdFromToken("fixture-access")).thenReturn(7L);
        when(jwt.getUsernameFromToken("fixture-access")).thenReturn("alice");
        when(jwt.getDeviceTypeFromToken("fixture-access")).thenReturn("desktop");
        when(users.isAccessTokenActive("fixture-access", 7L, "desktop")).thenReturn(true);
        var expected = example("http-conversations-response");
        when(conversations.getConversationSummaries(7L))
                .thenReturn(List.of(mapper.treeToValue(expected.at("/data/0"), ConversationSummary.class)));
        var listed = send(request("/api/v1/chat/conversations").header("Authorization", "Bearer fixture-access").GET());
        assertEquals(200, listed.statusCode());
        assertEquals(expected, mapper.readTree(listed.body()));

        when(users.refreshToken(any(TokenRefreshDTO.class))).thenAnswer(call -> {
            TokenRefreshDTO dto = call.getArgument(0);
            assertEquals("fixture-refresh", dto.getRefreshToken());
            var rotated = mapper.treeToValue(loginJson.get("data"), LoginVO.class);
            rotated.setRefreshToken("fixture-rotated");
            return rotated;
        });
        var refreshed = post("/api/v1/auth/refresh", "", "lanchat_refresh=fixture-refresh");
        assertEquals(200, refreshed.statusCode());
        assertTrue(refreshed.headers().firstValue("Set-Cookie").orElseThrow().contains("fixture-rotated"));
        assertEquals(loginJson, mapper.readTree(refreshed.body()));
        var logout = send(request("/api/v1/auth/logout").header("Cookie", "lanchat_refresh=fixture-rotated")
                .header("Authorization", "Bearer fixture-access").POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(200, logout.statusCode());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        verify(users).logoutByToken(7L, "fixture-access");
        verify(users).logoutByRefreshToken("fixture-rotated");
    }
}
