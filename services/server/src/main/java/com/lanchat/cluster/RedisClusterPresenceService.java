package com.lanchat.cluster;

import com.lanchat.config.LanChatNodeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Global presence stored as expiring sorted-set members.
 *
 * <p>Only identifiers and expiration timestamps are stored. User profiles,
 * access tokens and WebSocket payloads are never persisted in presence keys.</p>
 */
@Component
public class RedisClusterPresenceService implements ClusterPresenceService {

    private static final Logger log = LoggerFactory.getLogger(RedisClusterPresenceService.class);

    private static final DefaultRedisScript<Long> REGISTER_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local expires = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            local before = redis.call('ZCARD', KEYS[1])
            redis.call('ZADD', KEYS[1], expires, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5]))
            local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            if #latest > 0 then
              redis.call('ZADD', KEYS[2], latest[2], ARGV[4])
            end
            return before
            """, Long.class);

    private static final DefaultRedisScript<Long> UNREGISTER_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            redis.call('ZREM', KEYS[1], ARGV[2])
            local after = redis.call('ZCARD', KEYS[1])
            if after == 0 then
              redis.call('DEL', KEYS[1])
              local transitioned = redis.call('ZREM', KEYS[2], ARGV[3])
              if transitioned == 0 then
                return -1
              end
            else
              local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
              redis.call('ZADD', KEYS[2], latest[2], ARGV[3])
            end
            return after
            """, Long.class);

    private static final DefaultRedisScript<Long> ONLINE_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            local count = redis.call('ZCARD', KEYS[1])
            if count == 0 then
              redis.call('DEL', KEYS[1])
            end
            return count
            """, Long.class);

    /**
     * Atomically claims an expired global user only if no live per-session lease
     * exists. A concurrent heartbeat repairs the global score instead of producing
     * a false offline transition; ZREM makes the claim single-winner across nodes.
     */
    private static final DefaultRedisScript<Long> RECONCILE_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            local count = redis.call('ZCARD', KEYS[1])
            if count > 0 then
              local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
              redis.call('ZADD', KEYS[2], latest[2], ARGV[2])
              return 0
            end
            redis.call('DEL', KEYS[1])
            local score = redis.call('ZSCORE', KEYS[2], ARGV[2])
            if score and tonumber(score) <= now then
              return redis.call('ZREM', KEYS[2], ARGV[2])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LanChatClusterProperties clusterProperties;
    private final String instanceId;
    private final String keyPrefix;
    private final String onlineUsersKey;
    private final ConcurrentHashMap<String, PresenceRegistration> localRegistrations =
            new ConcurrentHashMap<>();
    private final AtomicReference<Consumer<Set<Long>>> globalOfflineListener =
            new AtomicReference<>();

    public RedisClusterPresenceService(StringRedisTemplate redisTemplate,
                                       LanChatClusterProperties clusterProperties,
                                       LanChatNodeProperties nodeProperties) {
        this.redisTemplate = redisTemplate;
        this.clusterProperties = clusterProperties;
        this.instanceId = clusterProperties.resolvedInstanceId();
        String clusterId = clusterProperties.resolvedClusterId(nodeProperties);
        this.keyPrefix = "lanchat:{" + clusterId + "}:presence:user:";
        this.onlineUsersKey = "lanchat:{" + clusterId + "}:presence:users";
    }

    @Override
    public boolean register(Long userId,
                            Long deviceId,
                            String sessionId,
                            boolean becameLocallyOnline) {
        if (!clusterProperties.isEnabled() || userId == null) return becameLocallyOnline;
        String member = member(deviceId, sessionId);
        localRegistrations.put(member, new PresenceRegistration(userId, deviceId, sessionId));
        try {
            long now = System.currentTimeMillis();
            long ttlMillis = clusterProperties.effectivePresenceTtlSeconds() * 1_000;
            Long before = redisTemplate.execute(
                    REGISTER_SCRIPT,
                    List.of(userKey(userId), onlineUsersKey),
                    Long.toString(now),
                    Long.toString(now + ttlMillis),
                    member,
                    userId.toString(),
                    Long.toString(ttlMillis * 2)
            );
            return before == null
                    ? becameLocallyOnline
                    : becameLocallyOnline && before == 0;
        } catch (RuntimeException exception) {
            warnFallback("注册", userId, exception);
            return becameLocallyOnline;
        }
    }

    @Override
    public void refresh(Long userId, Long deviceId, String sessionId) {
        if (!clusterProperties.isEnabled() || userId == null) return;
        try {
            long now = System.currentTimeMillis();
            long ttlMillis = clusterProperties.effectivePresenceTtlSeconds() * 1_000;
            redisTemplate.execute(
                    REGISTER_SCRIPT,
                    List.of(userKey(userId), onlineUsersKey),
                    Long.toString(now),
                    Long.toString(now + ttlMillis),
                    member(deviceId, sessionId),
                    userId.toString(),
                    Long.toString(ttlMillis * 2)
            );
        } catch (RuntimeException exception) {
            warnFallback("刷新", userId, exception);
        }
    }

    @Override
    public boolean unregister(Long userId,
                              Long deviceId,
                              String sessionId,
                              boolean becameLocallyOffline) {
        if (!clusterProperties.isEnabled() || userId == null) return becameLocallyOffline;
        localRegistrations.remove(member(deviceId, sessionId));
        try {
            Long remaining = redisTemplate.execute(
                    UNREGISTER_SCRIPT,
                    List.of(userKey(userId), onlineUsersKey),
                    Long.toString(System.currentTimeMillis()),
                    member(deviceId, sessionId),
                    userId.toString()
            );
            return remaining == null
                    ? becameLocallyOffline
                    : becameLocallyOffline && remaining == 0;
        } catch (RuntimeException exception) {
            warnFallback("注销", userId, exception);
            return becameLocallyOffline;
        }
    }

    @Override
    public boolean isUserOnline(Long userId, boolean locallyOnline) {
        if (!clusterProperties.isEnabled() || userId == null) return locallyOnline;
        try {
            Long count = redisTemplate.execute(
                    ONLINE_SCRIPT,
                    List.of(userKey(userId), onlineUsersKey),
                    Long.toString(System.currentTimeMillis()),
                    userId.toString()
            );
            return locallyOnline || (count != null && count > 0);
        } catch (RuntimeException exception) {
            warnFallback("查询", userId, exception);
            return locallyOnline;
        }
    }

    @Override
    public boolean isUserDefinitelyOffline(Long userId, boolean locallyOnline) {
        if (locallyOnline) return false;
        if (!clusterProperties.isEnabled() || userId == null) return userId != null;
        try {
            Long count = redisTemplate.execute(
                    ONLINE_SCRIPT,
                    List.of(userKey(userId), onlineUsersKey),
                    Long.toString(System.currentTimeMillis()),
                    userId.toString()
            );
            return count != null && count == 0;
        } catch (RuntimeException exception) {
            warnFallback("确认离线", userId, exception);
            return false;
        }
    }

    @Override
    public Set<Long> getOnlineUserIds(Set<Long> localUserIds) {
        Set<Long> fallback = localUserIds == null ? Set.of() : Set.copyOf(localUserIds);
        if (!clusterProperties.isEnabled()) return fallback;
        try {
            long now = System.currentTimeMillis();
            Set<String> values = redisTemplate.opsForZSet()
                    .rangeByScore(onlineUsersKey, now + 1, Double.POSITIVE_INFINITY);
            if (values == null) return fallback;
            Set<Long> result = new LinkedHashSet<>(fallback);
            for (String value : values) {
                try {
                    long userId = Long.parseLong(value);
                    if (userId > 0) result.add(userId);
                } catch (NumberFormatException ignored) {
                    // Ignore data not written by the current schema.
                }
            }
            return Set.copyOf(result);
        } catch (RuntimeException exception) {
            log.warn("Redis 全局在线列表不可用，已降级到本实例: {}", exception.getMessage());
            return fallback;
        }
    }

    @Override
    public void bindGlobalOfflineListener(Consumer<Set<Long>> listener) {
        globalOfflineListener.set(listener);
    }

    private String userKey(Long userId) {
        return keyPrefix + userId;
    }

    private String member(Long deviceId, String sessionId) {
        String safeSessionId = sessionId == null ? "unknown"
                : sessionId.replaceAll("[^A-Za-z0-9_.-]", "-");
        safeSessionId = safeSessionId.substring(0, Math.min(96, safeSessionId.length()));
        return instanceId + ":" + safeSessionId + ":" + (deviceId == null ? 0 : deviceId);
    }

    private void warnFallback(String operation, Long userId, RuntimeException exception) {
        log.warn("Redis presence {}失败，使用本实例状态: userId={}, error={}",
                operation, userId, exception.getMessage());
    }

    /**
     * Re-registers this process' live sessions before their Redis TTL expires.
     * Client PING traffic also refreshes presence, while this heartbeat covers idle clients
     * and repopulates Redis after a restart.
     */
    @Scheduled(
            fixedDelayString = "#{@lanChatClusterProperties.effectivePresenceHeartbeatSeconds()}",
            timeUnit = TimeUnit.SECONDS
    )
    void refreshRegisteredSessions() {
        if (!clusterProperties.isEnabled()) return;
        localRegistrations.forEach((member, registration) ->
                localRegistrations.computeIfPresent(member, (ignored, current) -> {
                    if (current.equals(registration)) {
                        refresh(current.userId(), current.deviceId(), current.sessionId());
                    }
                    return current;
                }));
    }

    @Scheduled(
            fixedDelayString = "#{@lanChatClusterProperties.effectivePresenceHeartbeatSeconds()}",
            timeUnit = TimeUnit.SECONDS
    )
    void reconcileExpiredUsers() {
        if (!clusterProperties.isEnabled()) return;
        Consumer<Set<Long>> listener = globalOfflineListener.get();
        if (listener == null) return;
        try {
            long now = System.currentTimeMillis();
            Set<String> candidates = redisTemplate.opsForZSet().rangeByScore(
                    onlineUsersKey,
                    Double.NEGATIVE_INFINITY,
                    now,
                    0,
                    100);
            if (candidates == null || candidates.isEmpty()) return;

            Set<Long> expiredUsers = new LinkedHashSet<>();
            for (String candidate : candidates) {
                Long userId = parseUserId(candidate);
                if (userId == null) continue;
                Long claimed = redisTemplate.execute(
                        RECONCILE_SCRIPT,
                        List.of(userKey(userId), onlineUsersKey),
                        Long.toString(now),
                        userId.toString());
                if (Long.valueOf(1L).equals(claimed)) expiredUsers.add(userId);
            }
            if (!expiredUsers.isEmpty()) listener.accept(Set.copyOf(expiredUsers));
        } catch (RuntimeException exception) {
            log.warn("Redis 全局在线租约对账失败: {}", exception.getMessage());
        }
    }

    private Long parseUserId(String value) {
        if (value == null) return null;
        try {
            long userId = Long.parseLong(value);
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record PresenceRegistration(Long userId, Long deviceId, String sessionId) {
    }
}
