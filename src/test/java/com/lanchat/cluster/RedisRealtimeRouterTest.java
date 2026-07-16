package com.lanchat.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.config.LanChatNodeProperties;
import com.lanchat.dto.WebSocketEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRealtimeRouterTest {

    @Test
    void redisFailureNeverPreventsLocalDelivery() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.convertAndSend(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        RedisRealtimeRouter router = router(redis, "instance-a");
        RecordingDelivery local = new RecordingDelivery();
        router.bind(local);

        router.sendToUser(7L, envelope("FRIEND_CHANGED"));

        assertEquals(List.of(7L), local.users);
        assertEquals(List.of("FRIEND_CHANGED"), local.events);
    }

    @Test
    void subscriberDeliversAllScopesLocallyOnceWithoutRepublishing() {
        StringRedisTemplate publisherRedis = mock(StringRedisTemplate.class);
        RedisRealtimeRouter publisher = router(publisherRedis, "instance-a");
        publisher.bind(new RecordingDelivery());

        publisher.sendToUser(7L, envelope("USER_EVENT"));
        publisher.sendToDevice(8L, 81L, envelope("DEVICE_EVENT"));
        publisher.broadcast(envelope("ALL_EVENT"));

        @SuppressWarnings("unchecked")
        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(publisherRedis, org.mockito.Mockito.times(3))
                .convertAndSend(anyString(), payloadCaptor.capture());

        StringRedisTemplate subscriberRedis = mock(StringRedisTemplate.class);
        RedisRealtimeRouter subscriber = router(subscriberRedis, "instance-b");
        RecordingDelivery local = new RecordingDelivery();
        subscriber.bind(local);
        for (String payload : payloadCaptor.getAllValues()) {
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            subscriber.accept(body);
            subscriber.accept(body);
        }

        assertEquals(List.of(7L), local.users);
        assertEquals(List.of(new DeviceTarget(8L, 81L)), local.devices);
        assertEquals(1, local.broadcasts);
        assertEquals(List.of("USER_EVENT", "DEVICE_EVENT", "ALL_EVENT"), local.events);
        verify(subscriberRedis, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void deliveryReceiptRunsOnlyAfterRemoteWebSocketSendAcknowledges() {
        StringRedisTemplate publisherRedis = mock(StringRedisTemplate.class);
        when(publisherRedis.convertAndSend(anyString(), anyString())).thenReturn(1L);
        RedisRealtimeRouter publisher = router(publisherRedis, "instance-a");
        publisher.bind(new RecordingDelivery());
        AtomicInteger receipts = new AtomicInteger();

        publisher.sendToUserWithReceipt(
                7L, envelope("BROADCAST"), receipts::incrementAndGet);

        assertEquals(0, receipts.get());
        assertEquals(1, publisher.pendingReceiptCount());
        var eventPayload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(publisherRedis).convertAndSend(anyString(), eventPayload.capture());

        StringRedisTemplate subscriberRedis = mock(StringRedisTemplate.class);
        when(subscriberRedis.convertAndSend(anyString(), anyString())).thenReturn(1L);
        RedisRealtimeRouter subscriber = router(subscriberRedis, "instance-b");
        RecordingDelivery remote = new RecordingDelivery();
        remote.acknowledgeUserDeliveries = true;
        subscriber.bind(remote);
        subscriber.accept(eventPayload.getValue().getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(7L), remote.users);
        var ackPayload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(subscriberRedis).convertAndSend(anyString(), ackPayload.capture());
        byte[] ack = ackPayload.getValue().getBytes(StandardCharsets.UTF_8);
        publisher.accept(ack);
        publisher.accept(ack);

        assertEquals(1, receipts.get());
        assertEquals(0, publisher.pendingReceiptCount());
    }

    @Test
    void failedOrUnsubscribedRouteNeverProducesDeliveryReceipt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.convertAndSend(anyString(), anyString())).thenReturn(0L);
        RedisRealtimeRouter router = router(redis, "instance-a");
        router.bind(new RecordingDelivery());
        AtomicInteger receipts = new AtomicInteger();

        router.sendToUserWithReceipt(7L, envelope("BROADCAST"), receipts::incrementAndGet);

        assertEquals(0, receipts.get());
        assertEquals(0, router.pendingReceiptCount());
    }

    @Test
    void deduplicationStateNeverExceedsConfiguredMaximum() {
        RedisRealtimeRouter router = router(mock(StringRedisTemplate.class), "instance-a");
        long now = System.currentTimeMillis();
        for (int index = 0; index < 20_100; index++) {
            assertTrue(router.markSeen(eventId(index), now));
        }

        assertEquals(20_000, router.seenEventCount());
        assertTrue(router.markSeen(eventId(0), now));
        assertFalse(router.markSeen(eventId(20_099), now));
        assertEquals(20_000, router.seenEventCount());
    }

    private String eventId(int value) {
        String hex = Integer.toHexString(value);
        return "0".repeat(32 - hex.length()) + hex;
    }

    private RedisRealtimeRouter router(StringRedisTemplate redis, String instanceId) {
        LanChatNodeProperties node = new LanChatNodeProperties();
        node.setId("logical-node");
        LanChatClusterProperties cluster = new LanChatClusterProperties();
        cluster.setEnabled(true);
        cluster.setId("logical-cluster");
        cluster.setInstanceId(instanceId);
        return new RedisRealtimeRouter(new ObjectMapper(), redis, cluster, node);
    }

    private WebSocketEnvelope envelope(String eventName) {
        WebSocketEnvelope envelope = new WebSocketEnvelope();
        envelope.setVersion(1);
        envelope.setEvent(eventName);
        envelope.setTimestamp(System.currentTimeMillis());
        envelope.setPayload(Map.of("message", eventName));
        return envelope;
    }

    private static final class RecordingDelivery implements LocalRealtimeDelivery {
        private final List<Long> users = new ArrayList<>();
        private final List<DeviceTarget> devices = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private int broadcasts;
        private boolean acknowledgeUserDeliveries;

        @Override
        public void sendToUser(Long userId, WebSocketEnvelope event) {
            users.add(userId);
            events.add(event.getEvent());
        }

        @Override
        public int sendToUserWithReceipt(Long userId, WebSocketEnvelope event) {
            sendToUser(userId, event);
            return acknowledgeUserDeliveries ? 1 : 0;
        }

        @Override
        public void sendToDevice(Long userId, Long deviceId, WebSocketEnvelope event) {
            devices.add(new DeviceTarget(userId, deviceId));
            events.add(event.getEvent());
        }

        @Override
        public void broadcast(WebSocketEnvelope event) {
            broadcasts++;
            events.add(event.getEvent());
        }
    }

    private record DeviceTarget(Long userId, Long deviceId) {
    }
}
