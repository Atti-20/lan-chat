package com.lanchat.cluster;

import com.lanchat.config.LanChatNodeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisClusterPresenceServiceTest {

    @Test
    void disabledClusterUsesOnlyCallerSuppliedLocalState() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisClusterPresenceService presence = service(redis, false);

        assertTrue(presence.register(7L, 70L, "session-a", true));
        assertFalse(presence.register(7L, 70L, "session-a", false));
        assertTrue(presence.isUserOnline(7L, true));
        assertTrue(presence.isUserDefinitelyOffline(7L, false));
        assertTrue(presence.unregister(7L, 70L, "session-a", true));
        assertEquals(Set.of(7L, 8L), presence.getOnlineUserIds(Set.of(7L, 8L)));
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void redisFailureFallsBackToLocalTransitions() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        RedisClusterPresenceService presence = service(redis, true);

        assertTrue(presence.register(7L, 70L, "session-a", true));
        assertTrue(presence.isUserOnline(7L, true));
        assertFalse(presence.isUserDefinitelyOffline(7L, false));
        assertTrue(presence.unregister(7L, 70L, "session-a", true));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void localConnectionsRemainOnlineWhenRedisStateIsTemporarilyEmpty() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.rangeByScore(any(), anyDouble(), anyDouble()))
                .thenReturn(Set.of("8"));
        RedisClusterPresenceService presence = service(redis, true);

        assertTrue(presence.isUserOnline(7L, true));
        assertEquals(Set.of(7L, 8L), presence.getOnlineUserIds(Set.of(7L)));
        verify(zSet, never()).removeRangeByScore(any(), anyDouble(), anyDouble());

        when(zSet.rangeByScore(any(), anyDouble(), anyDouble())).thenReturn(null);
        assertEquals(Set.of(7L), presence.getOnlineUserIds(Set.of(7L)));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void expiredRemoteLeaseIsClaimedOnlyOnceAcrossReconciliationRuns() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.rangeByScore(any(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of("7"));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 0L);
        RedisClusterPresenceService presence = service(redis, true);
        List<Set<Long>> transitions = new ArrayList<>();
        presence.bindGlobalOfflineListener(transitions::add);

        presence.reconcileExpiredUsers();
        presence.reconcileExpiredUsers();

        assertEquals(List.of(Set.of(7L)), transitions);
        verify(redis, times(2)).execute(
                any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void liveLeaseRepairsGlobalScoreWithoutOfflineTransition() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.rangeByScore(any(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of("7"));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);
        RedisClusterPresenceService presence = service(redis, true);
        List<Set<Long>> transitions = new ArrayList<>();
        presence.bindGlobalOfflineListener(transitions::add);

        presence.reconcileExpiredUsers();

        assertTrue(transitions.isEmpty());
    }

    private RedisClusterPresenceService service(StringRedisTemplate redis, boolean enabled) {
        LanChatNodeProperties node = new LanChatNodeProperties();
        node.setId("logical-node");
        LanChatClusterProperties cluster = new LanChatClusterProperties();
        cluster.setEnabled(enabled);
        cluster.setId("logical-cluster");
        cluster.setInstanceId("instance-a");
        return new RedisClusterPresenceService(redis, cluster, node);
    }
}
