package com.lanchat.cluster;

import java.util.Set;
import java.util.function.Consumer;

/** Redis-backed global presence with explicit local fallbacks. */
public interface ClusterPresenceService {

    boolean register(Long userId,
                     Long deviceId,
                     String sessionId,
                     boolean becameLocallyOnline);

    void refresh(Long userId, Long deviceId, String sessionId);

    boolean unregister(Long userId,
                       Long deviceId,
                       String sessionId,
                       boolean becameLocallyOffline);

    boolean isUserOnline(Long userId, boolean locallyOnline);

    /**
     * Returns true only when the implementation can positively establish that no
     * live session exists. Redis failures must therefore return false.
     */
    default boolean isUserDefinitelyOffline(Long userId, boolean locallyOnline) {
        return !isUserOnline(userId, locallyOnline);
    }

    Set<Long> getOnlineUserIds(Set<Long> localUserIds);

    /** Binds the owner of durable online state to Redis lease-expiry transitions. */
    default void bindGlobalOfflineListener(Consumer<Set<Long>> listener) {
        // Local presence has no remote leases to reconcile.
    }
}
