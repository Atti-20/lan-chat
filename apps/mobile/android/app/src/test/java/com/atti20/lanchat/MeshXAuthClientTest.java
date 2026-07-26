package com.atti20.lanchat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.Cookie;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class MeshXAuthClientTest {
    private MockWebServer server;
    private MemorySessionStore store;
    private MeshXAuthClient client;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new MemorySessionStore();
        client = new MeshXAuthClient(store);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void loginAndRefreshCookieRotationSurviveClientRestart() throws Exception {
        server.enqueue(authResponse("access-1", "refresh-1"));
        server.enqueue(authResponse("access-2", "refresh-2"));
        server.enqueue(authResponse("access-3", "refresh-3"));

        MeshXAuthClient.AuthSession login = client.login(
                origin(server), "/api/v1", "atti", "secret", "Pixel 10");
        assertEquals("access-1", login.token());

        MeshXAuthClient.AuthSession refresh = client.refresh(
                origin(server), "/api/v1", "Pixel 10");
        assertEquals("access-2", refresh.token());

        MeshXAuthClient restarted = new MeshXAuthClient(store);
        MeshXAuthClient.AuthSession afterRestart = restarted.refresh(
                origin(server), "/api/v1", "Pixel 10");
        assertEquals("access-3", afterRestart.token());

        RecordedRequest loginRequest = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest firstRefresh = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest secondRefresh = server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(loginRequest.getBody().readUtf8().contains("\"deviceType\":\"android\""));
        assertTrue(firstRefresh.getBody().readUtf8().contains("\"deviceType\":\"android\""));
        assertEquals(null, loginRequest.getHeader("Cookie"));
        assertTrue(firstRefresh.getHeader("Cookie").contains("lanchat_refresh=refresh-1"));
        assertTrue(secondRefresh.getHeader("Cookie").contains("lanchat_refresh=refresh-2"));
    }

    @Test
    public void isolatesCookiesBySchemeHostAndPort() throws Exception {
        try (MockWebServer other = new MockWebServer()) {
            other.start();
            server.enqueue(authResponse("a-login", "origin-a"));
            other.enqueue(authResponse("b-login", "origin-b"));
            server.enqueue(authResponse("a-refresh", "origin-a-next"));
            other.enqueue(authResponse("b-refresh", "origin-b-next"));

            client.login(origin(server), "/api/v1", "a", "password", "A");
            client.login(origin(other), "/api/v1", "b", "password", "B");
            client.refresh(origin(server), "/api/v1", "A");
            client.refresh(origin(other), "/api/v1", "B");

            server.takeRequest(1, TimeUnit.SECONDS);
            other.takeRequest(1, TimeUnit.SECONDS);
            String cookieA = server.takeRequest(1, TimeUnit.SECONDS).getHeader("Cookie");
            String cookieB = other.takeRequest(1, TimeUnit.SECONDS).getHeader("Cookie");
            assertTrue(cookieA.contains("lanchat_refresh=origin-a"));
            assertFalse(cookieA.contains("origin-b"));
            assertTrue(cookieB.contains("lanchat_refresh=origin-b"));
            assertFalse(cookieB.contains("origin-a"));
        }
    }

    @Test
    public void rejectsRedirectWithoutContactingRedirectTarget() throws Exception {
        try (MockWebServer target = new MockWebServer()) {
            target.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", target.url("/stolen")));

            MeshXAuthClient.AuthException error = assertThrows(
                    MeshXAuthClient.AuthException.class,
                    () -> client.login(origin(server), "/api/v1", "atti", "secret", "Pixel"));
            assertTrue(error.getMessage().contains("redirects are not allowed"));
            assertEquals(0, target.getRequestCount());
            assertTrue(store.load(origin(server)).isEmpty());
        }
    }

    @Test
    public void failedLoginDoesNotReplaceExistingSession() throws Exception {
        server.enqueue(authResponse("old-access", "old-refresh"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":401,\"msg\":\"denied\",\"data\":null}"));
        server.enqueue(authResponse("still-valid", "rotated-refresh"));

        client.login(origin(server), "/api/v1", "atti", "secret", "Pixel");
        MeshXAuthClient.AuthException denied = assertThrows(MeshXAuthClient.AuthException.class,
                () -> client.login(origin(server), "/api/v1", "atti", "wrong", "Pixel"));
        // Ordinary authentication failures must stay distinguishable from the
        // AUTH_BUSY concurrency guard.
        assertFalse(denied instanceof MeshXAuthClient.BusyException);

        new MeshXAuthClient(store).refresh(origin(server), "/api/v1", "Pixel");
        server.takeRequest(1, TimeUnit.SECONDS);
        server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest refresh = server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(refresh.getHeader("Cookie").contains("lanchat_refresh=old-refresh"));
    }

    @Test
    public void timesOutUsingTheConfiguredTotalCallDeadline() {
        client = new MeshXAuthClient(store, 100L, 150L);
        server.enqueue(new MockResponse()
                .setHeadersDelay(2, TimeUnit.SECONDS)
                .setBody(successBody("late-access")));

        MeshXAuthClient.AuthException error = assertThrows(
                MeshXAuthClient.AuthException.class,
                () -> client.login(origin(server), "/api/v1", "atti", "secret", "Pixel"));
        assertTrue(error.getMessage().contains("timed out"));
        assertTrue(store.load(origin(server)).isEmpty());
    }

    @Test
    public void logoutNetworkFailureStillClearsPersistedSession() throws Exception {
        client = new MeshXAuthClient(store, 100L, 250L);
        server.enqueue(authResponse("access", "refresh"));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        client.login(origin(server), "/api/v1", "atti", "secret", "Pixel");

        assertThrows(MeshXAuthClient.AuthException.class,
                () -> client.logout(origin(server), "/api/v1", "access"));
        assertTrue(store.load(origin(server)).isEmpty());
        assertThrows(MeshXAuthClient.AuthException.class,
                () -> new MeshXAuthClient(store).refresh(origin(server), "/api/v1", "Pixel"));
    }

    @Test
    public void logoutWithBearerStillContactsNodeWhenRefreshCookieIsMissing() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"success\",\"data\":null}"));

        client.logout(origin(server), "/api/v1", "bearer-without-cookie");

        RecordedRequest logout = server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(logout != null);
        assertEquals("/api/v1/auth/logout", logout.getPath());
        assertEquals("Bearer bearer-without-cookie", logout.getHeader("Authorization"));
        assertEquals(null, logout.getHeader("Cookie"));
        assertTrue(store.load(origin(server)).isEmpty());
        assertThrows(MeshXAuthClient.AuthException.class,
                () -> client.refresh(origin(server), "/api/v1", "Pixel"));
    }

    @Test
    public void clearIsIdempotent() throws Exception {
        client.clearNodeSession(origin(server));
        client.clearNodeSession(origin(server));
        assertTrue(store.load(origin(server)).isEmpty());
    }

    @Test
    public void clearInvalidatesAnInFlightLoginBeforeItCanPersistCookies() throws Exception {
        server.enqueue(authResponse("late-access", "late-refresh")
                .setHeadersDelay(300, TimeUnit.MILLISECONDS));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MeshXAuthClient.AuthSession> login = executor.submit(() -> client.login(
                    origin(server), "/api/v1", "atti", "secret", "Pixel"));
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null);
            client.clearNodeSession(origin(server));

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> login.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof MeshXAuthClient.AuthException);
            assertTrue(failure.getCause().getMessage().contains("superseded"));
            assertTrue(store.load(origin(server)).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void clearInvalidatesAnInFlightRefreshBeforeCookieRotationCanPersist() throws Exception {
        server.enqueue(authResponse("access", "refresh-before"));
        server.enqueue(authResponse("late-access", "refresh-after")
                .setHeadersDelay(300, TimeUnit.MILLISECONDS));
        client.login(origin(server), "/api/v1", "atti", "secret", "Pixel");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MeshXAuthClient.AuthSession> refresh = executor.submit(() -> client.refresh(
                    origin(server), "/api/v1", "Pixel"));
            server.takeRequest(1, TimeUnit.SECONDS);
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null);
            client.clearNodeSession(origin(server));

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> refresh.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof MeshXAuthClient.AuthException);
            assertTrue(failure.getCause().getMessage().contains("superseded"));
            assertTrue(store.load(origin(server)).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentRefreshRejectsTheSecondCallAndKeepsTheSuccessfulRotation()
            throws Exception {
        server.enqueue(authResponse("access", "refresh-before"));
        server.enqueue(authResponse("rotated-access", "refresh-after")
                .setHeadersDelay(300, TimeUnit.MILLISECONDS));
        server.enqueue(authResponse("restart-access", "refresh-final"));
        client.login(origin(server), "/api/v1", "atti", "secret", "Pixel");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MeshXAuthClient.AuthSession> first = executor.submit(() -> client.refresh(
                    origin(server), "/api/v1", "Pixel"));
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null);
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null);

            // The busy path throws the dedicated type so the plugin can map it
            // to the AUTH_BUSY error code.
            MeshXAuthClient.BusyException concurrent = assertThrows(
                    MeshXAuthClient.BusyException.class,
                    () -> client.refresh(origin(server), "/api/v1", "Pixel"));
            assertTrue(concurrent.getMessage().contains("already in progress"));
            assertEquals("rotated-access", first.get(2, TimeUnit.SECONDS).token());

            MeshXAuthClient restarted = new MeshXAuthClient(store);
            assertEquals("restart-access",
                    restarted.refresh(origin(server), "/api/v1", "Pixel").token());
            RecordedRequest restartedRefresh = server.takeRequest(1, TimeUnit.SECONDS);
            assertTrue(restartedRefresh.getHeader("Cookie")
                    .contains("lanchat_refresh=refresh-after"));
            assertEquals(3, server.getRequestCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void switchingNodesClearsOriginASessionAndRotatesOriginB() throws Exception {
        try (MockWebServer other = new MockWebServer()) {
            other.start();
            server.enqueue(authResponse("a-access", "a-refresh"));
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":200,\"msg\":\"success\",\"data\":null}"));
            other.enqueue(authResponse("b-access", "b-refresh"));
            other.enqueue(authResponse("b-rotated", "b-refresh-next"));
            other.enqueue(authResponse("b-rotated-again", "b-refresh-final"));

            client.login(origin(server), "/api/v1", "atti", "secret", "Pixel");
            client.logout(origin(server), "/api/v1", "a-access");
            client.login(origin(other), "/api/v1", "atti", "secret", "Pixel");
            assertEquals("b-rotated",
                    client.refresh(origin(other), "/api/v1", "Pixel").token());

            // Node A keeps no session after logout: the store is empty and a
            // refresh fails before any network request reaches node A again.
            assertTrue(store.load(origin(server)).isEmpty());
            assertThrows(MeshXAuthClient.AuthException.class,
                    () -> client.refresh(origin(server), "/api/v1", "Pixel"));
            assertEquals(2, server.getRequestCount());

            // Node B's refresh rotated its cookie; the next refresh presents
            // the rotated value and node A's cookie never leaks to node B.
            assertEquals("b-rotated-again",
                    client.refresh(origin(other), "/api/v1", "Pixel").token());
            other.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest firstRefreshB = other.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest secondRefreshB = other.takeRequest(1, TimeUnit.SECONDS);
            assertTrue(firstRefreshB.getHeader("Cookie").contains("lanchat_refresh=b-refresh"));
            assertFalse(firstRefreshB.getHeader("Cookie").contains("b-refresh-next"));
            assertTrue(secondRefreshB.getHeader("Cookie").contains("lanchat_refresh=b-refresh-next"));
            assertFalse(firstRefreshB.getHeader("Cookie").contains("a-refresh"));
            assertFalse(secondRefreshB.getHeader("Cookie").contains("a-refresh"));
        }
    }

    @Test
    public void validatesOriginAndApiPathBeforeNetworkAccess() {
        assertEquals("https://example.test:8443",
                uncheckedNormalize("HTTPS://Example.Test:8443/"));
        assertThrows(MeshXAuthClient.AuthException.class,
                () -> client.login("https://example.test/api", "/api/v1", "a", "b", "Pixel"));
        assertThrows(MeshXAuthClient.AuthException.class,
                () -> client.login(origin(server), "/api/v2", "a", "b", "Pixel"));
        assertEquals(0, server.getRequestCount());
    }

    private static String uncheckedNormalize(String origin) {
        try {
            return MeshXAuthClient.normalizeOrigin(origin);
        } catch (MeshXAuthClient.AuthException exception) {
            throw new AssertionError(exception);
        }
    }

    private static MockResponse authResponse(String accessToken, String refreshToken) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Set-Cookie", "lanchat_refresh=" + refreshToken
                        + "; Max-Age=3600; Path=/api/v1/auth; HttpOnly; SameSite=Strict")
                .setBody(successBody(accessToken));
    }

    private static String successBody(String token) {
        return "{\"code\":200,\"msg\":\"success\",\"data\":{"
                + "\"userId\":7,\"username\":\"atti\",\"nickname\":\"Atti\","
                + "\"avatar\":null,\"token\":\"" + token + "\","
                + "\"refreshToken\":\"must-never-cross-the-bridge\",\"expiresIn\":900}}";
    }

    private static String origin(MockWebServer mockWebServer) {
        String value = mockWebServer.url("/").toString();
        return value.substring(0, value.length() - 1);
    }

    private static final class MemorySessionStore implements OriginSessionStore {
        private final Map<String, List<Cookie>> values = new HashMap<>();

        @Override
        public synchronized List<Cookie> load(String origin) {
            return values.getOrDefault(origin, List.of());
        }

        @Override
        public synchronized void save(String origin, List<Cookie> cookies) {
            values.put(origin, List.copyOf(cookies));
        }

        @Override
        public synchronized void remove(String origin) {
            values.remove(origin);
        }
    }
}
