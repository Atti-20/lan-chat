package com.meshx.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/**
 * Native authentication transport. Refresh cookies never cross the Capacitor
 * boundary and every request uses a jar scoped to one canonical origin.
 */
final class MeshXAuthClient {
    static final String API_BASE_PATH = "/api/v1";
    private static final String REFRESH_COOKIE = "lanchat_refresh";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_COOKIES = 32;
    private static final int MAX_COOKIE_BYTES = 32 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OriginSessionStore store;
    private final OkHttpClient baseClient;
    private final Map<String, OriginState> origins = new ConcurrentHashMap<>();

    MeshXAuthClient(OriginSessionStore store) {
        this(store, 5_000L, 12_000L);
    }

    MeshXAuthClient(OriginSessionStore store, long connectTimeoutMillis, long callTimeoutMillis) {
        this.store = store;
        this.baseClient = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    AuthSession login(
            String rawOrigin,
            String apiBasePath,
            String username,
            String password,
            String deviceName
    ) throws AuthException {
        CanonicalOrigin origin = canonicalOrigin(rawOrigin);
        requireApiBasePath(apiBasePath);
        validateLogin(username, password, deviceName);

        OriginState state = stateFor(origin);
        long revision = beginOperation(state);
        try {
            StagedCookieJar cookies = new StagedCookieJar(origin.url(), List.of());
            JSONObject body = jsonBody(
                    "username", username,
                    "password", password,
                    "deviceType", "android",
                    "deviceName", normalizedDeviceName(deviceName));
            AuthSession session = executeAuth(
                    client(cookies), request(origin, "/auth/login", body, null));
            if (!cookies.hasRefreshCookie()) {
                throw new AuthException("node did not establish a native refresh session");
            }
            commit(origin, state, revision, cookies);
            return session;
        } finally {
            finishOperation(state);
        }
    }

    AuthSession refresh(String rawOrigin, String apiBasePath, String deviceName)
            throws AuthException {
        CanonicalOrigin origin = canonicalOrigin(rawOrigin);
        requireApiBasePath(apiBasePath);
        validateDeviceName(deviceName);

        OriginState state = stateFor(origin);
        Operation operation = beginSessionOperation(origin, state);
        try {
            StagedCookieJar cookies = new StagedCookieJar(
                    origin.url(), operation.session().cookies());
            JSONObject body = jsonBody(
                    "deviceType", "android",
                    "deviceName", normalizedDeviceName(deviceName));
            AuthSession session = executeAuth(
                    client(cookies), request(origin, "/auth/refresh", body, null));
            if (!cookies.hasRefreshCookie()) {
                throw new AuthException("node did not retain a native refresh session");
            }
            commit(origin, state, operation.revision(), cookies);
            return session;
        } finally {
            finishOperation(state);
        }
    }

    void logout(String rawOrigin, String apiBasePath, String accessToken) throws AuthException {
        CanonicalOrigin origin = canonicalOrigin(rawOrigin);
        requireApiBasePath(apiBasePath);
        validateAccessToken(accessToken);
        OriginState state = stateFor(origin);
        Operation operation = beginLogoutOperation(state);

        AuthException requestFailure = null;
        RuntimeException clearFailure = null;
        try {
            StagedCookieJar cookies = new StagedCookieJar(origin.url(), operation.session().cookies());
            if (cookies.hasRefreshCookie() || (accessToken != null && !accessToken.isBlank())) {
                executeUnit(client(cookies), request(origin, "/auth/logout", new JSONObject(), accessToken));
            }
        } catch (AuthException exception) {
            requestFailure = exception;
        } finally {
            try {
                finishLogout(origin, state, operation.revision());
            } catch (RuntimeException exception) {
                clearFailure = exception;
            } finally {
                finishOperation(state);
            }
        }
        if (requestFailure != null) {
            if (clearFailure != null) requestFailure.addSuppressed(clearFailure);
            throw requestFailure;
        }
        if (clearFailure != null) throw clearFailure;
    }

    void clearNodeSession(String rawOrigin) throws AuthException {
        CanonicalOrigin origin = canonicalOrigin(rawOrigin);
        OriginState state = stateFor(origin);
        synchronized (state) {
            state.revision++;
            state.loaded = true;
            state.session = null;
            store.remove(origin.value());
        }
    }

    /**
     * Cheap view of the same per-origin guard {@link #requireIdle} enforces,
     * for callers that would otherwise queue behind an in-flight request
     * instead of observing it. Never touches the session store, so it is safe
     * to call from latency-sensitive threads. {@code requireIdle} remains the
     * defensive invariant inside every operation.
     */
    boolean isBusy(String rawOrigin) {
        CanonicalOrigin origin;
        try {
            origin = canonicalOrigin(rawOrigin);
        } catch (AuthException exception) {
            // An invalid origin can never hold the guard; the subsequent
            // operation reports the proper validation error.
            return false;
        }
        OriginState state = origins.get(origin.value());
        if (state == null) return false;
        synchronized (state) {
            return state.operationInFlight;
        }
    }

    private OriginState stateFor(CanonicalOrigin origin) {
        OriginState state = origins.computeIfAbsent(origin.value(), ignored -> new OriginState());
        synchronized (state) {
            if (!state.loaded) {
                List<Cookie> cookies = validCookies(origin.url(), store.load(origin.value()));
                state.session = hasRefreshCookie(cookies) ? new NativeSession(cookies) : null;
                state.loaded = true;
            }
        }
        return state;
    }

    private static long beginOperation(OriginState state) throws AuthException {
        synchronized (state) {
            requireIdle(state);
            state.operationInFlight = true;
            return ++state.revision;
        }
    }

    private Operation beginSessionOperation(CanonicalOrigin origin, OriginState state)
            throws AuthException {
        synchronized (state) {
            requireIdle(state);
            state.operationInFlight = true;
            long revision = ++state.revision;
            NativeSession session = state.session;
            if (session == null || !hasRefreshCookie(session.cookies())) {
                state.operationInFlight = false;
                state.session = null;
                store.remove(origin.value());
                throw new MissingSessionException(
                        "no native refresh session exists for this node");
            }
            return new Operation(revision, session);
        }
    }

    private static Operation beginLogoutOperation(OriginState state) throws AuthException {
        synchronized (state) {
            requireIdle(state);
            state.operationInFlight = true;
            long revision = ++state.revision;
            NativeSession session = state.session;
            return new Operation(
                    revision,
                    session == null ? new NativeSession(List.of()) : session);
        }
    }

    private static void requireIdle(OriginState state) throws AuthException {
        if (state.operationInFlight) {
            throw new BusyException("a native authentication request is already in progress");
        }
    }

    private static void finishOperation(OriginState state) {
        synchronized (state) {
            state.operationInFlight = false;
        }
    }

    private void commit(
            CanonicalOrigin origin,
            OriginState state,
            long expectedRevision,
            StagedCookieJar jar
    ) throws AuthException {
        List<Cookie> cookies = jar.snapshot();
        synchronized (state) {
            if (state.revision != expectedRevision) {
                throw new AuthException("native authentication request was superseded");
            }
            store.save(origin.value(), cookies);
            state.session = new NativeSession(cookies);
        }
    }

    private void finishLogout(CanonicalOrigin origin, OriginState state, long expectedRevision) {
        synchronized (state) {
            if (state.revision != expectedRevision) return;
            state.session = null;
            store.remove(origin.value());
        }
    }

    private OkHttpClient client(CookieJar cookieJar) {
        // newBuilder shares the dispatcher and connection pool, while each
        // request still receives an isolated staged CookieJar.
        return baseClient.newBuilder()
                .cookieJar(cookieJar)
                .build();
    }

    private static Request request(
            CanonicalOrigin origin,
            String authPath,
            JSONObject body,
            String accessToken
    ) {
        Request.Builder request = new Request.Builder()
                .url(origin.value() + API_BASE_PATH + authPath)
                .header("Accept", "application/json")
                .header("User-Agent", "MeshX-Android/" + BuildConfig.VERSION_NAME)
                .post(RequestBody.create(body.toString(), JSON));
        if (accessToken != null && !accessToken.isBlank()) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return request.build();
    }

    private static JSONObject jsonBody(Object... namesAndValues) throws AuthException {
        JSONObject body = new JSONObject();
        try {
            for (int index = 0; index < namesAndValues.length; index += 2) {
                body.put((String) namesAndValues[index], namesAndValues[index + 1]);
            }
            return body;
        } catch (Exception exception) {
            throw new AuthException("unable to construct native authentication request", exception);
        }
    }

    private static AuthSession executeAuth(OkHttpClient client, Request request)
            throws AuthException {
        try (Response response = execute(client, request)) {
            JSONObject result = parseResult(response);
            Object rawData = result.opt("data");
            if (!(rawData instanceof JSONObject data)) {
                throw new AuthException("authentication response did not contain session data");
            }
            long userId = requiredPositiveLong(data, "userId");
            String username = requiredString(data, "username", 100);
            String nickname = optionalString(data, "nickname", 100);
            if (nickname == null) nickname = username;
            String avatar = optionalString(data, "avatar", 2_048);
            String token = requiredString(data, "token", 8_192);
            long expiresIn = requiredPositiveLong(data, "expiresIn");
            // Constructing from an explicit allow-list guarantees that a
            // refreshToken field can never be returned to JavaScript.
            return new AuthSession(userId, username, nickname, avatar, token, expiresIn);
        }
    }

    private static void executeUnit(OkHttpClient client, Request request) throws AuthException {
        try (Response response = execute(client, request)) {
            parseResult(response);
        }
    }

    private static Response execute(OkHttpClient client, Request request) throws AuthException {
        Call call = client.newCall(request);
        try {
            Response response = call.execute();
            if (response.isRedirect()) {
                response.close();
                throw new AuthException("node authentication redirects are not allowed");
            }
            return response;
        } catch (InterruptedIOException exception) {
            throw new AuthException("node authentication request timed out", exception);
        } catch (IOException exception) {
            throw new AuthException("unable to connect to the selected node", exception);
        }
    }

    private static JSONObject parseResult(Response response) throws AuthException {
        JSONObject result;
        try {
            result = new JSONObject(readBody(response.body()));
        } catch (AuthException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AuthException(
                    "node returned an invalid authentication response (HTTP " + response.code() + ")",
                    exception);
        }
        Object rawCode = result.opt("code");
        int code = rawCode instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
        if (code != 200) {
            String message = optionalString(result, "msg", 300);
            throw new AuthException(message == null || message.isBlank()
                    ? "authentication request failed with code " + code
                    : sanitizedMessage(message));
        }
        if (!response.isSuccessful()) {
            throw new AuthException("authentication request failed with HTTP " + response.code());
        }
        return result;
    }

    private static String readBody(ResponseBody body) throws IOException, AuthException {
        if (body == null) throw new AuthException("node returned an empty authentication response");
        long declaredLength = body.contentLength();
        if (declaredLength > MAX_RESPONSE_BYTES) {
            throw new AuthException("node authentication response was too large");
        }
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new AuthException("node authentication response was too large");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String normalizeOrigin(String rawOrigin) throws AuthException {
        return canonicalOrigin(rawOrigin).value();
    }

    private static CanonicalOrigin canonicalOrigin(String rawOrigin) throws AuthException {
        if (rawOrigin == null || rawOrigin.isBlank() || rawOrigin.length() > 2_048) {
            throw new AuthException("node origin is invalid");
        }
        HttpUrl url = HttpUrl.parse(rawOrigin.trim());
        if (url == null || !("http".equals(url.scheme()) || "https".equals(url.scheme()))) {
            throw new AuthException("node origin must use HTTP or HTTPS");
        }
        if (!url.encodedUsername().isEmpty() || !url.encodedPassword().isEmpty()
                || !"/".equals(url.encodedPath()) || url.encodedQuery() != null
                || url.fragment() != null) {
            throw new AuthException("node origin must not contain credentials, a path, query, or fragment");
        }
        String canonical = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString();
        canonical = canonical.substring(0, canonical.length() - 1);
        HttpUrl root = HttpUrl.parse(canonical + "/");
        if (root == null) throw new AuthException("node origin is invalid");
        return new CanonicalOrigin(canonical, root);
    }

    private static void requireApiBasePath(String value) throws AuthException {
        if (!API_BASE_PATH.equals(value)) {
            throw new AuthException("node API base path is not supported");
        }
    }

    private static void validateLogin(String username, String password, String deviceName)
            throws AuthException {
        if (username == null || username.isBlank() || username.length() > 100) {
            throw new AuthException("username is empty or too long");
        }
        if (password == null || password.isEmpty() || password.length() > 1_024) {
            throw new AuthException("password is empty or too long");
        }
        validateDeviceName(deviceName);
    }

    private static void validateDeviceName(String value) throws AuthException {
        if (value != null && value.length() > 100) {
            throw new AuthException("device name is too long");
        }
    }

    private static String normalizedDeviceName(String value) {
        if (value == null) return "MeshX Android";
        StringBuilder normalized = new StringBuilder();
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(100)
                .forEach(normalized::appendCodePoint);
        String result = normalized.toString().trim();
        return result.isEmpty() ? "MeshX Android" : result;
    }

    private static void validateAccessToken(String accessToken) throws AuthException {
        if (accessToken == null) return;
        if (accessToken.length() > 8_192 || accessToken.codePoints().anyMatch(Character::isISOControl)) {
            throw new AuthException("access token is invalid");
        }
    }

    private static long requiredPositiveLong(JSONObject object, String name) throws AuthException {
        Object value = object.opt(name);
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw new AuthException("authentication response field " + name + " is invalid");
        }
        return number.longValue();
    }

    private static String requiredString(JSONObject object, String name, int maxLength)
            throws AuthException {
        String value = optionalString(object, name, maxLength);
        if (value == null || value.isBlank()) {
            throw new AuthException("authentication response field " + name + " is invalid");
        }
        return value;
    }

    private static String optionalString(JSONObject object, String name, int maxLength)
            throws AuthException {
        Object value = object.opt(name);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof String text) || text.length() > maxLength) {
            throw new AuthException("authentication response field " + name + " is invalid");
        }
        return text;
    }

    private static String sanitizedMessage(String value) {
        StringBuilder sanitized = new StringBuilder();
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(300)
                .forEach(sanitized::appendCodePoint);
        return sanitized.toString().trim();
    }

    private static List<Cookie> validCookies(HttpUrl origin, List<Cookie> values) {
        StagedCookieJar jar = new StagedCookieJar(origin, values == null ? List.of() : values);
        return jar.snapshot();
    }

    private static boolean hasRefreshCookie(List<Cookie> cookies) {
        long now = System.currentTimeMillis();
        return cookies.stream().anyMatch(cookie -> REFRESH_COOKIE.equals(cookie.name())
                && cookie.persistent() && cookie.expiresAt() > now && !cookie.value().isEmpty());
    }

    record AuthSession(
            long userId,
            String username,
            String nickname,
            String avatar,
            String token,
            long expiresIn
    ) {}

    static class AuthException extends Exception {
        AuthException(String message) {
            super(message);
        }

        AuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class MissingSessionException extends AuthException {
        private MissingSessionException(String message) {
            super(message);
        }
    }

    /**
     * Distinct type for the "another request is in progress" guard so the
     * plugin can map it to a stable AUTH_BUSY error code without matching on
     * message text.
     */
    static final class BusyException extends AuthException {
        private BusyException(String message) {
            super(message);
        }
    }

    private record CanonicalOrigin(String value, HttpUrl url) {}

    private record NativeSession(List<Cookie> cookies) {
        private NativeSession {
            cookies = List.copyOf(cookies);
        }
    }

    private record Operation(long revision, NativeSession session) {}

    private static final class OriginState {
        private boolean loaded;
        private boolean operationInFlight;
        private long revision;
        private NativeSession session;
    }

    private static final class StagedCookieJar implements CookieJar {
        private final HttpUrl origin;
        private final HttpUrl authEndpoint;
        private final Map<String, Cookie> cookies = new LinkedHashMap<>();

        private StagedCookieJar(HttpUrl origin, List<Cookie> initialCookies) {
            this.origin = origin;
            this.authEndpoint = origin.resolve(API_BASE_PATH + "/auth/refresh");
            if (authEndpoint == null) throw new IllegalArgumentException("invalid authentication endpoint");
            for (Cookie cookie : initialCookies) add(cookie);
            removeExpired();
        }

        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> responseCookies) {
            if (!sameOrigin(origin, url)) return;
            for (Cookie cookie : responseCookies) add(cookie);
            removeExpired();
        }

        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            if (!sameOrigin(origin, url)) return List.of();
            removeExpired();
            List<Cookie> matching = new ArrayList<>();
            for (Cookie cookie : cookies.values()) {
                if (cookie.matches(url)) matching.add(cookie);
            }
            return List.copyOf(matching);
        }

        synchronized List<Cookie> snapshot() {
            removeExpired();
            return List.copyOf(cookies.values());
        }

        synchronized boolean hasRefreshCookie() {
            return MeshXAuthClient.hasRefreshCookie(snapshot());
        }

        private void add(Cookie cookie) {
            if (!cookie.matches(authEndpoint)) return;
            String encoded = cookie.toString();
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_COOKIE_BYTES) return;
            String key = cookie.name() + '\n' + cookie.domain() + '\n' + cookie.path();
            if (cookie.expiresAt() <= System.currentTimeMillis() || cookie.value().isEmpty()) {
                cookies.remove(key);
            } else {
                cookies.put(key, cookie);
                while (cookies.size() > MAX_COOKIES) {
                    cookies.remove(cookies.keySet().iterator().next());
                }
            }
        }

        private void removeExpired() {
            long now = System.currentTimeMillis();
            cookies.values().removeIf(cookie -> cookie.expiresAt() <= now);
        }

        private static boolean sameOrigin(HttpUrl expected, HttpUrl actual) {
            return expected.scheme().equals(actual.scheme())
                    && expected.host().equals(actual.host())
                    && expected.port() == actual.port();
        }
    }
}
