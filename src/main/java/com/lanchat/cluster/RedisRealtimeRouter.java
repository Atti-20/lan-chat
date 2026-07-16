package com.lanchat.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.config.LanChatNodeProperties;
import com.lanchat.dto.WebSocketEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local-first Redis Pub/Sub router.
 *
 * <p>The publisher always performs local delivery first. Redis failures therefore
 * never break a single-node deployment. Subscribers invoke only the local delivery
 * callback and never republish, which prevents routing loops.</p>
 */
@Component
public class RedisRealtimeRouter implements RealtimeRouter {

    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeRouter.class);
    private static final int MAX_ROUTE_BYTES = 128 * 1024;
    private static final long MAX_EVENT_AGE_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long MAX_FUTURE_SKEW_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final int MAX_DEDUP_ENTRIES = 20_000;
    private static final int MAX_PENDING_RECEIPTS = 10_000;
    private static final long RECEIPT_TIMEOUT_MILLIS = Duration.ofSeconds(30).toMillis();

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final LanChatClusterProperties clusterProperties;
    private final String clusterId;
    private final String instanceId;
    private final String channel;
    private final AtomicReference<LocalRealtimeDelivery> localDelivery = new AtomicReference<>();
    private final Object seenEventMonitor = new Object();
    private final LinkedHashMap<String, Long> seenEventIds = new LinkedHashMap<>();
    private final Object receiptMonitor = new Object();
    private final LinkedHashMap<String, PendingReceipt> pendingReceipts = new LinkedHashMap<>();

    public RedisRealtimeRouter(ObjectMapper objectMapper,
                               StringRedisTemplate redisTemplate,
                               LanChatClusterProperties clusterProperties,
                               LanChatNodeProperties nodeProperties) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.clusterProperties = clusterProperties;
        this.clusterId = clusterProperties.resolvedClusterId(nodeProperties);
        this.instanceId = clusterProperties.resolvedInstanceId();
        this.channel = clusterProperties.resolvedChannel(nodeProperties);
    }

    @Override
    public void bind(LocalRealtimeDelivery delivery) {
        if (delivery == null) throw new IllegalArgumentException("本地实时投递器不能为空");
        localDelivery.set(delivery);
    }

    @Override
    public void sendToUser(Long userId, WebSocketEnvelope envelope) {
        if (userId == null || envelope == null) return;
        route(newEvent(RealtimeRouteScope.USER, List.of(userId), null, false, envelope), null);
    }

    @Override
    public void sendToUserWithReceipt(Long userId,
                                      WebSocketEnvelope envelope,
                                      Runnable onDelivered) {
        if (userId == null || envelope == null || onDelivered == null) return;
        route(newEvent(RealtimeRouteScope.USER, List.of(userId), null, true, envelope), onDelivered);
    }

    @Override
    public void sendToDevice(Long userId, Long deviceId, WebSocketEnvelope envelope) {
        if (userId == null || deviceId == null || envelope == null) return;
        route(newEvent(RealtimeRouteScope.DEVICE, List.of(userId), deviceId, false, envelope), null);
    }

    @Override
    public void broadcast(WebSocketEnvelope envelope) {
        if (envelope == null) return;
        route(newEvent(RealtimeRouteScope.ALL, List.of(), null, false, envelope), null);
    }

    /** Redis listener entrypoint. This method performs local delivery only. */
    public void accept(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_ROUTE_BYTES) return;
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.hasNonNull("ackEventId")) {
                acceptAck(objectMapper.treeToValue(root, RealtimeDeliveryAck.class));
                return;
            }
            RealtimeRouteEvent event = objectMapper.treeToValue(root, RealtimeRouteEvent.class);
            if (!valid(event) || !markSeen(event.eventId(), event.createdAt())) return;
            int delivered = deliverLocal(event);
            if (event.deliveryReceiptRequested() && delivered > 0) publishAck(event);
        } catch (Exception exception) {
            log.warn("忽略无法解析的跨实例实时事件: {}", exception.getMessage());
        }
    }

    public String channel() {
        return channel;
    }

    public String instanceId() {
        return instanceId;
    }

    private void route(RealtimeRouteEvent event, Runnable onDelivered) {
        if (onDelivered != null) registerReceipt(event.eventId(), onDelivered);
        markSeen(event.eventId(), event.createdAt());
        int delivered = deliverLocal(event);
        if (delivered > 0) completeReceipt(event.eventId());
        if (!clusterProperties.isEnabled()) {
            discardReceipt(event.eventId());
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(event);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ROUTE_BYTES) {
                log.warn("跨实例实时事件超过大小限制: event={}", event.envelope().getEvent());
                discardReceipt(event.eventId());
                return;
            }
            Long subscribers = redisTemplate.convertAndSend(channel, json);
            if (event.deliveryReceiptRequested()
                    && delivered == 0
                    && (subscribers == null || subscribers <= 0)) {
                discardReceipt(event.eventId());
            }
        } catch (RuntimeException exception) {
            discardReceipt(event.eventId());
            log.warn("Redis 跨实例实时路由不可用，已保留本实例投递: event={}, error={}",
                    event.envelope().getEvent(), exception.getMessage());
        } catch (Exception exception) {
            discardReceipt(event.eventId());
            log.warn("跨实例实时事件序列化失败: event={}, error={}",
                    event.envelope().getEvent(), exception.getMessage());
        }
    }

    private int deliverLocal(RealtimeRouteEvent event) {
        LocalRealtimeDelivery delivery = localDelivery.get();
        if (delivery == null) return 0;
        return switch (event.scope()) {
            case USER -> {
                if (event.deliveryReceiptRequested()) {
                    int delivered = 0;
                    for (Long userId : event.targetUserIds()) {
                        delivered += Math.max(0,
                                delivery.sendToUserWithReceipt(userId, event.envelope()));
                    }
                    yield delivered;
                }
                event.targetUserIds().forEach(userId ->
                        delivery.sendToUser(userId, event.envelope()));
                yield 0;
            }
            case DEVICE -> {
                delivery.sendToDevice(
                        event.targetUserIds().get(0), event.targetDeviceId(), event.envelope());
                yield 0;
            }
            case ALL -> {
                delivery.broadcast(event.envelope());
                yield 0;
            }
        };
    }

    private RealtimeRouteEvent newEvent(RealtimeRouteScope scope,
                                        List<Long> targetUserIds,
                                        Long targetDeviceId,
                                        boolean deliveryReceiptRequested,
                                        WebSocketEnvelope envelope) {
        return new RealtimeRouteEvent(
                1,
                UUID.randomUUID().toString().replace("-", ""),
                clusterId,
                instanceId,
                scope,
                List.copyOf(targetUserIds),
                targetDeviceId,
                System.currentTimeMillis(),
                deliveryReceiptRequested,
                envelope
        );
    }

    private boolean valid(RealtimeRouteEvent event) {
        if (event == null || event.version() != 1 || event.scope() == null
                || event.envelope() == null || !clusterId.equals(event.clusterId())
                || !StringUtils.hasText(event.eventId())
                || !event.eventId().matches("^[a-f0-9]{32}$")) {
            return false;
        }
        String envelopeEvent = event.envelope().getEvent();
        if (!StringUtils.hasText(envelopeEvent) || "AUTH".equalsIgnoreCase(envelopeEvent)) return false;
        long now = System.currentTimeMillis();
        if (event.createdAt() < now - MAX_EVENT_AGE_MILLIS
                || event.createdAt() > now + MAX_FUTURE_SKEW_MILLIS) return false;
        List<Long> targets = event.targetUserIds() == null ? List.of() : event.targetUserIds();
        if (targets.size() > 1_000 || targets.stream().anyMatch(id -> id == null || id <= 0)) return false;
        return switch (event.scope()) {
            case ALL -> !event.deliveryReceiptRequested()
                    && targets.isEmpty() && event.targetDeviceId() == null;
            case USER -> targets.size() == 1 && event.targetDeviceId() == null;
            case DEVICE -> !event.deliveryReceiptRequested() && targets.size() == 1
                    && event.targetDeviceId() != null && event.targetDeviceId() > 0;
        };
    }

    boolean markSeen(String eventId, long createdAt) {
        if (!StringUtils.hasText(eventId)) return false;
        synchronized (seenEventMonitor) {
            long cutoff = System.currentTimeMillis() - MAX_EVENT_AGE_MILLIS;
            Long previous = seenEventIds.get(eventId);
            if (previous != null && previous >= cutoff) return false;
            if (previous != null) seenEventIds.remove(eventId);
            while (seenEventIds.size() >= MAX_DEDUP_ENTRIES) {
                Iterator<String> oldest = seenEventIds.keySet().iterator();
                if (!oldest.hasNext()) break;
                oldest.next();
                oldest.remove();
            }
            seenEventIds.put(eventId, createdAt);
            return true;
        }
    }

    int seenEventCount() {
        synchronized (seenEventMonitor) {
            return seenEventIds.size();
        }
    }

    int pendingReceiptCount() {
        synchronized (receiptMonitor) {
            return pendingReceipts.size();
        }
    }

    @Scheduled(fixedDelay = 30, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    void expirePendingReceipts() {
        synchronized (receiptMonitor) {
            removeExpiredReceipts(System.currentTimeMillis());
        }
    }

    private void registerReceipt(String eventId, Runnable callback) {
        synchronized (receiptMonitor) {
            long now = System.currentTimeMillis();
            removeExpiredReceipts(now);
            while (pendingReceipts.size() >= MAX_PENDING_RECEIPTS) {
                Iterator<String> oldest = pendingReceipts.keySet().iterator();
                if (!oldest.hasNext()) break;
                oldest.next();
                oldest.remove();
            }
            pendingReceipts.put(eventId,
                    new PendingReceipt(now + RECEIPT_TIMEOUT_MILLIS, callback));
        }
    }

    private void completeReceipt(String eventId) {
        PendingReceipt receipt;
        synchronized (receiptMonitor) {
            receipt = pendingReceipts.remove(eventId);
        }
        if (receipt == null || receipt.expiresAt() < System.currentTimeMillis()) return;
        try {
            receipt.callback().run();
        } catch (RuntimeException exception) {
            log.warn("跨实例投递回执处理失败: eventId={}, error={}",
                    eventId, exception.getMessage());
        }
    }

    private void discardReceipt(String eventId) {
        synchronized (receiptMonitor) {
            pendingReceipts.remove(eventId);
        }
    }

    private void removeExpiredReceipts(long now) {
        Iterator<PendingReceipt> receipts = pendingReceipts.values().iterator();
        while (receipts.hasNext()) {
            if (receipts.next().expiresAt() >= now) break;
            receipts.remove();
        }
    }

    private void publishAck(RealtimeRouteEvent event) {
        if (!StringUtils.hasText(event.originInstanceId())) return;
        RealtimeDeliveryAck ack = new RealtimeDeliveryAck(
                1,
                event.eventId(),
                clusterId,
                event.originInstanceId(),
                System.currentTimeMillis());
        try {
            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(ack));
        } catch (Exception exception) {
            log.warn("跨实例投递回执发布失败: eventId={}, error={}",
                    event.eventId(), exception.getMessage());
        }
    }

    private void acceptAck(RealtimeDeliveryAck ack) {
        if (!validAck(ack)) return;
        completeReceipt(ack.ackEventId());
    }

    private boolean validAck(RealtimeDeliveryAck ack) {
        if (ack == null || ack.version() != 1
                || !clusterId.equals(ack.clusterId())
                || !instanceId.equals(ack.targetInstanceId())
                || !StringUtils.hasText(ack.ackEventId())
                || !ack.ackEventId().matches("^[a-f0-9]{32}$")) {
            return false;
        }
        long now = System.currentTimeMillis();
        return ack.createdAt() >= now - MAX_EVENT_AGE_MILLIS
                && ack.createdAt() <= now + MAX_FUTURE_SKEW_MILLIS;
    }

    private record PendingReceipt(long expiresAt, Runnable callback) {
    }
}
