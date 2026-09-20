package com.lanchat.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.WebSocketEnvelope;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.security.LoginUser;
import com.lanchat.service.ConversationService;
import com.lanchat.service.UserService;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class MobilePushService {
    private static final Logger log = LoggerFactory.getLogger(MobilePushService.class);
    private final PushConfiguration config;
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final ConversationService conversations;
    private final ObjectMapper json;
    private final HttpClient http;
    @org.springframework.beans.factory.annotation.Autowired
    public MobilePushService(PushConfiguration config, JdbcTemplate jdbc, UserService users, ConversationService conversations, ObjectMapper json) {
        this(config, jdbc, users, conversations, json, HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(8)).build());
    }
    MobilePushService(PushConfiguration config, JdbcTemplate jdbc, UserService users, ConversationService conversations, ObjectMapper json, HttpClient http) {
        this.http = http;
        this.config = config; this.jdbc = jdbc; this.users = users; this.conversations = conversations; this.json = json;
    }
    private DeviceLogin device(LoginUser user) {
        if (user == null) throw new AccessDeniedException("请先登录");
        DeviceLogin device = users.getActiveDevice(user.getToken(), user.getUserId(), user.getDeviceType());
        if (device == null) throw new AccessDeniedException("设备会话已失效");
        return device;
    }
    public void validate(PushSubscriptionRequest request) {
        if (request == null || request.scope() == null || !request.scope().matches("[A-Za-z0-9_-]{32,80}")) throw new IllegalArgumentException("推送绑定无效");
        if ("APNS".equals(request.platform())) {
            if (!config.apnsReady()) throw new IllegalStateException("节点未配置APNs");
            if (request.endpoint() == null || !request.endpoint().matches("[a-fA-F0-9]{64,200}")) throw new IllegalArgumentException("设备推送令牌无效");
        } else if ("FCM".equals(request.platform())) {
            if (!config.fcmReady()) throw new IllegalStateException("节点未配置FCM");
            if (request.endpoint() == null || !request.endpoint().matches("[A-Za-z0-9:_-]{32,500}")) throw new IllegalArgumentException("设备推送令牌无效");
        } else throw new IllegalArgumentException("推送平台无效");
    }
    @Transactional
    public void register(LoginUser user, PushSubscriptionRequest request) {
        if (!config.isEnabled()) throw new IllegalStateException("节点未启用后台推送");
        DeviceLogin device = device(user); validate(request);
        Integer same = jdbc.queryForObject("SELECT COUNT(*) FROM mobile_push_subscription WHERE device_id=? AND user_id=? AND platform=? AND endpoint=? AND scope=?", Integer.class, device.getId(), user.getUserId(), request.platform(), request.endpoint(), request.scope());
        if (same != null && same == 1) return;
        jdbc.update("DELETE FROM mobile_push_delivery WHERE device_id=?", device.getId());
        jdbc.update("INSERT INTO mobile_push_subscription(device_id,user_id,platform,endpoint,scope) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),platform=VALUES(platform),endpoint=VALUES(endpoint),scope=VALUES(scope),updated_at=CURRENT_TIMESTAMP", device.getId(), user.getUserId(), request.platform(), request.endpoint(), request.scope());
    }
    @Transactional
    public void unregister(LoginUser user) {
        if (!config.isEnabled()) return;
        DeviceLogin device = device(user);
        jdbc.update("DELETE FROM mobile_push_delivery WHERE device_id=?", device.getId());
        jdbc.update("DELETE FROM mobile_push_subscription WHERE device_id=? AND user_id=?", device.getId(), user.getUserId());
    }
    public void enqueue(Long userId, WebSocketEnvelope event) {
        if (!config.isEnabled() || userId == null || event == null) return;
        String type = event.getEvent();
        if (!Set.of("CHAT_DELIVER", "BROADCAST").contains(type)) return;
        Map<?, ?> payload = event.getPayload() == null ? Map.of() : event.getPayload();
        if ("CHAT_DELIVER".equals(type) && payload.get("fromUserId") instanceof Number n && n.longValue() == userId) return;
        try {
            String identity = Objects.toString(payload.get("messageId"), "");
            if (identity.isBlank()) identity = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(payload)));
            if (identity.length() > 128) return;
            jdbc.update("INSERT IGNORE INTO mobile_push_delivery(device_id,event_key,conversation_id,scope) SELECT s.device_id,?,?,s.scope FROM mobile_push_subscription s JOIN device_login d ON d.id=s.device_id WHERE s.user_id=? AND d.user_id=s.user_id AND d.status=1 AND d.expire_time>CURRENT_TIMESTAMP", type + ":" + identity, event.getConversationId(), userId);
        } catch (Exception e) { log.warn("Mobile push enqueue failed; chat delivery is unaffected ({})", e.getClass().getSimpleName()); }
    }
    @Scheduled(fixedDelayString = "${meshx.push.poll-ms:5000}")
    public void deliverPending() {
        if (!config.isEnabled()) return;
        try {
            var rows = jdbc.queryForList("SELECT q.device_id,q.event_key,q.attempts,q.conversation_id,s.user_id,s.platform,s.endpoint,s.scope FROM mobile_push_delivery q JOIN mobile_push_subscription s ON s.device_id=q.device_id AND s.scope=q.scope JOIN device_login d ON d.id=s.device_id JOIN user u ON u.id=s.user_id WHERE q.attempts>=0 AND q.attempts<8 AND q.next_attempt_at<=CURRENT_TIMESTAMP AND q.created_at>DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY) AND d.user_id=s.user_id AND d.status=1 AND d.expire_time>CURRENT_TIMESTAMP AND u.status=1 AND u.archived_at IS NULL ORDER BY q.next_attempt_at LIMIT 20");
            for (var row : rows) deliver(row);
            jdbc.update("DELETE FROM mobile_push_delivery WHERE created_at<DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY)");
            jdbc.update("DELETE s FROM mobile_push_subscription s LEFT JOIN device_login d ON d.id=s.device_id WHERE d.id IS NULL OR d.status<>1 OR d.expire_time<=CURRENT_TIMESTAMP");
        } catch (Exception e) { log.warn("Mobile push worker unavailable ({})", e.getClass().getSimpleName()); }
    }
    private void deliver(Map<String, Object> row) {
        long device = ((Number) row.get("device_id")).longValue(); String eventKey = (String) row.get("event_key");
        String lease = UUID.randomUUID().toString();
        // A compare-and-set lease prevents concurrent server workers sending a row together.
        if (jdbc.update("UPDATE mobile_push_delivery SET lease_token=?,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 MINUTE) WHERE device_id=? AND event_key=? AND scope=? AND next_attempt_at<=CURRENT_TIMESTAMP AND attempts>=0", lease, device, eventKey, row.get("scope")) != 1) return;
        try {
            String conversationId = (String) row.get("conversation_id");
            if (conversationId != null && !conversations.canAccess(conversationId, ((Number) row.get("user_id")).longValue())) { finish(device, eventKey, lease); return; }
            if (eventKey.startsWith("CHAT_DELIVER:")) {
                Integer unread = jdbc.queryForObject("SELECT COUNT(*) FROM chat_message m JOIN conversation_member c ON c.conversation_id=m.conversation_id AND c.user_id=? WHERE m.message_id=? AND c.left_time IS NULL AND c.is_muted=0 AND m.sequence>c.last_read_sequence AND COALESCE(m.is_recalled,0)=0 AND COALESCE(m.status,0)<>2", Integer.class, row.get("user_id"), eventKey.substring("CHAT_DELIVER:".length()));
                if (unread == null || unread == 0) { finish(device, eventKey, lease); return; }
            }
            String scope = (String) row.get("scope");
            HttpRequest.Builder request;
            if ("APNS".equals(row.get("platform"))) {
                if (!config.apnsReady()) throw new IllegalStateException("APNs unavailable");
                String host = config.isApnsSandbox() ? "https://api.sandbox.push.apple.com" : "https://api.push.apple.com";
                request = HttpRequest.newBuilder(URI.create(host + "/3/device/" + row.get("endpoint")))
                    .header("authorization", "bearer " + apnsToken()).header("apns-topic", config.getApnsTopic())
                    .header("apns-push-type", "alert").header("apns-priority", "10").header("apns-expiration", Long.toString(Instant.now().plusSeconds(3600).getEpochSecond()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("aps", Map.of("alert", Map.of("title", "MeshX", "body", "你有新的协作消息"), "sound", "default"), "meshxScope", scope))));
            } else {
                if (!config.fcmReady()) throw new IllegalStateException("FCM unavailable");
                request = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + config.getFcmProjectId() + "/messages:send"))
                    .header("Authorization", "Bearer " + fcmToken()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("message", Map.of("token", row.get("endpoint"), "data", Map.of("meshxScope", scope), "android", Map.of("priority", "HIGH", "ttl", "3600s"))))));
            }
            int status = http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) { finish(device, eventKey, lease); }
            else if (status == 404 || status == 410) { jdbc.update("DELETE FROM mobile_push_subscription WHERE device_id=? AND scope=?", device, scope); finish(device, eventKey, lease); }
            else retry(device, eventKey, lease);
        } catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); retry(device, eventKey, lease); }
    }
    private void finish(long device, String key, String lease) { jdbc.update("UPDATE mobile_push_delivery SET attempts=-1 WHERE device_id=? AND event_key=? AND lease_token=?", device, key, lease); }
    private void retry(long device, String key, String lease) { jdbc.update("UPDATE mobile_push_delivery SET attempts=attempts+1,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 MINUTE) WHERE device_id=? AND event_key=? AND lease_token=?", device, key, lease); }
    private String cachedFcmToken;
    private Instant fcmTokenExpiry = Instant.EPOCH;
    private synchronized String fcmToken() throws Exception {
        if (cachedFcmToken != null && fcmTokenExpiry.isAfter(Instant.now())) return cachedFcmToken;
        var credentials = json.readTree(Files.readString(Path.of(config.getFcmServiceAccountFile())));
        if (!config.getFcmProjectId().equals(credentials.path("project_id").asText())) throw new IllegalStateException("Firebase project mismatch");
        String pem = credentials.path("private_key").asText().replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Instant now = Instant.now();
        String assertion = Jwts.builder().issuer(credentials.path("client_email").asText()).audience().single("https://oauth2.googleapis.com/token").issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600))).claim("scope", "https://www.googleapis.com/auth/firebase.messaging").signWith(key, Jwts.SIG.RS256).compact();
        var response = http.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + assertion)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("FCM authentication failed");
        var token = json.readTree(response.body());
        cachedFcmToken = token.path("access_token").asText();
        if (cachedFcmToken.isBlank()) throw new IllegalStateException("FCM token missing");
        fcmTokenExpiry = now.plusSeconds(Math.max(1, Math.min(3300, token.path("expires_in").asLong(3600) - 60)));
        return cachedFcmToken;
    }
    private volatile String cachedToken;
    private volatile Instant tokenCreated = Instant.EPOCH;
    private synchronized String apnsToken() throws Exception {
        if (cachedToken != null && tokenCreated.plusSeconds(1200).isAfter(Instant.now())) return cachedToken;
        String pem = Files.readString(Path.of(config.getApnsKeyFile()), StandardCharsets.US_ASCII).replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        tokenCreated = Instant.now();
        return cachedToken = Jwts.builder().header().keyId(config.getApnsKeyId()).and().issuer(config.getApnsTeamId()).issuedAt(Date.from(tokenCreated)).signWith(key, Jwts.SIG.ES256).compact();
    }
}
