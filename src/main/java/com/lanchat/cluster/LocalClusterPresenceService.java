package com.lanchat.cluster;

import java.util.Set;

/** Local presence used by legacy direct construction in unit tests. */
public final class LocalClusterPresenceService implements ClusterPresenceService {

    @Override
    public boolean register(Long userId, Long deviceId, String sessionId, boolean becameLocallyOnline) {
        return becameLocallyOnline;
    }

    @Override
    public void refresh(Long userId, Long deviceId, String sessionId) {
        // Local in-memory sessions need no TTL refresh.
    }

    @Override
    public boolean unregister(Long userId,
                              Long deviceId,
                              String sessionId,
                              boolean becameLocallyOffline) {
        return becameLocallyOffline;
    }

    @Override
    public boolean isUserOnline(Long userId, boolean locallyOnline) {
        return locallyOnline;
    }

    @Override
    public Set<Long> getOnlineUserIds(Set<Long> localUserIds) {
        return localUserIds == null ? Set.of() : Set.copyOf(localUserIds);
    }
}
