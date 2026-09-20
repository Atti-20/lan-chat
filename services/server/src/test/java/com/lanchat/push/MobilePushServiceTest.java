package com.lanchat.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.WebSocketEnvelope;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.security.LoginUser;
import com.lanchat.service.ConversationService;
import com.lanchat.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import java.net.http.HttpClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MobilePushServiceTest {
    final PushConfiguration config = new PushConfiguration();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final UserService users = mock(UserService.class);
    final ConversationService conversations = mock(ConversationService.class);
    final HttpClient http = mock(HttpClient.class);
    final MobilePushService service = new MobilePushService(config, jdbc, users, conversations, new ObjectMapper(), http);
    final LoginUser user = new LoginUser(1L, "test", "android", "test-session");
    void enable() {
        config.setEnabled(true);
        config.setFcmServiceAccountFile("/external/credentials.json");
        config.setFcmProjectId("meshx-test");
        config.setFcmApplicationId("public-application");
        config.setFcmApiKey("public-key");
        config.setFcmSenderId("123456");
    }
    PushSubscriptionRequest binding() { return new PushSubscriptionRequest("FCM", "x".repeat(64), "s".repeat(32)); }
    @Test void defaultOffDoesNotTouchDatabaseOrNetwork() {
        service.enqueue(1L, new WebSocketEnvelope());
        service.deliverPending();
        assertThrows(IllegalStateException.class, () -> service.register(user, binding()));
        verifyNoInteractions(jdbc, users, http);
    }
    @Test void staleSessionCannotRegisterAndTokensCannotBecomeUrls() {
        enable();
        assertThrows(AccessDeniedException.class, () -> service.register(user, binding()));
        verifyNoInteractions(jdbc, http);
        assertThrows(IllegalArgumentException.class, () -> service.validate(new PushSubscriptionRequest("FCM", "https://internal.invalid/", "s".repeat(32))));
        assertThrows(IllegalArgumentException.class, () -> service.validate(new PushSubscriptionRequest("unknown", "x".repeat(64), "s".repeat(32))));
    }
    @Test void identicalRegistrationKeepsQueuedWork() {
        enable(); DeviceLogin device = new DeviceLogin(); device.setId(7L);
        when(users.getActiveDevice("test-session", 1L, "android")).thenReturn(device);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(7L), eq(1L), eq("FCM"), eq("x".repeat(64)), eq("s".repeat(32)))).thenReturn(1);
        service.register(user, binding());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(http);
    }
    @Test void ownMessagesAndNonMessageHintsDoNotQueuePush() {
        enable(); WebSocketEnvelope event = new WebSocketEnvelope();
        event.setEvent("CHAT_DELIVER"); event.setPayload(Map.of("fromUserId", 1L, "messageId", "m"));
        service.enqueue(1L, event);
        event.setEvent("CHAT_READ"); service.enqueue(1L, event);
        event.setEvent("SYNC_REQUIRED"); service.enqueue(1L, event);
        verifyNoInteractions(jdbc, http);
    }
    @Test void queuePersistsIdentityNotMessageBodyOrCredentials() {
        enable(); WebSocketEnvelope event = new WebSocketEnvelope();
        event.setEvent("CHAT_DELIVER"); event.setConversationId("private:1:2");
        event.setPayload(Map.of("fromUserId", 2L, "messageId", "message-id", "content", "private body"));
        service.enqueue(1L, event);
        verify(jdbc).update(contains("INSERT IGNORE"), eq("CHAT_DELIVER:message-id"), eq("private:1:2"), eq(1L));
        verifyNoInteractions(http);
    }
    @Test void removedMemberIsSuppressedAfterClaimWithScopedCompletion() {
        enable();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("device_id", 7L, "event_key", "CHAT_DELIVER:m", "conversation_id", "private:1:2", "user_id", 1L, "scope", "s".repeat(32))));
        when(jdbc.update(contains("SET lease_token="), any(), eq(7L), eq("CHAT_DELIVER:m"), eq("s".repeat(32)))).thenReturn(1);
        when(conversations.canAccess("private:1:2", 1L)).thenReturn(false);
        service.deliverPending();
        verify(jdbc).update(contains("AND lease_token=?"), eq(7L), eq("CHAT_DELIVER:m"), anyString());
        verifyNoInteractions(http);
    }
    @Test void anotherWorkersLeaseCannotSend() {
        enable();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("device_id", 7L, "event_key", "CHAT_DELIVER:m", "scope", "s".repeat(32))));
        service.deliverPending();
        verifyNoInteractions(http, conversations);
    }
    @Test void publicConfigurationDoesNotExposeCredentialPaths() throws Exception {
        enable(); config.setApnsKeyFile("/external/private.p8");
        String value = new ObjectMapper().writeValueAsString(new MobilePushController(service, config).mobilePushConfiguration());
        assertTrue(value.contains("meshx-test"));
        assertFalse(value.contains("/external"));
        assertFalse(value.contains("private.p8"));
    }
    @Test void fcmUsesSignedStringAudienceAndOnlyGenericData(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        enable();
        var generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var credentials = directory.resolve("service-account.json");
        java.nio.file.Files.writeString(credentials, new ObjectMapper().writeValueAsString(Map.of("project_id", "meshx-test", "client_email", "service@meshx-test.iam.gserviceaccount.com", "private_key", "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----")));
        config.setFcmServiceAccountFile(credentials.toString());
        var response = mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"test-access-token\",\"expires_in\":3600}");
        doReturn(response).when(http).send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("device_id", 7L, "event_key", "CHAT_DELIVER:m", "conversation_id", "private:1:2", "user_id", 1L, "scope", "s".repeat(32), "platform", "FCM", "endpoint", "device-token")));
        when(jdbc.update(contains("SET lease_token="), any(), eq(7L), eq("CHAT_DELIVER:m"), eq("s".repeat(32)))).thenReturn(1);
        when(conversations.canAccess("private:1:2", 1L)).thenReturn(true);
        when(jdbc.queryForObject(contains("FROM chat_message"), eq(Integer.class), eq(1L), eq("m"))).thenReturn(1);
        service.deliverPending();
        var requests = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        verify(http, times(2)).send(requests.capture(), any(java.net.http.HttpResponse.BodyHandler.class));
        var auth = requests.getAllValues().get(0);
        assertEquals("https://oauth2.googleapis.com/token", auth.uri().toString());
        String assertion = body(auth).split("&assertion=")[1];
        var claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(assertion.split("\\.")[1]));
        assertTrue(claims.path("aud").isTextual());
        io.jsonwebtoken.Jwts.parser().verifyWith(pair.getPublic()).build().parseSignedClaims(assertion);
        var request = requests.getAllValues().get(1);
        assertEquals("https://fcm.googleapis.com/v1/projects/meshx-test/messages:send", request.uri().toString());
        var payload = new ObjectMapper().readTree(body(request)).path("message");
        assertEquals(1, payload.path("data").size());
        assertEquals("s".repeat(32), payload.path("data").path("meshxScope").asText());
        assertFalse(payload.has("notification"));
    }
    private static String body(java.net.http.HttpRequest request) throws Exception {
        var done = new java.util.concurrent.CompletableFuture<String>();
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(java.nio.ByteBuffer chunk) { byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part); }
            public void onError(Throwable failure) { done.completeExceptionally(failure); }
            public void onComplete() { done.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)); }
        });
        return done.get(2, java.util.concurrent.TimeUnit.SECONDS);
    }
}
