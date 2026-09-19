package com.lanchat.service;

import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;
import com.lanchat.entity.User;

/** Transactional source of broadcast-card persistence and durable realtime intents. */
public interface BroadcastNoticeOutboxService {

    void queueOverview(Broadcast broadcast, BroadcastReceiver receiver, User sender);

    void queueReminder(Broadcast broadcast, BroadcastReceiver receiver, User sender, Long operatorId);

    /** Redacts persisted cards and writes durable recall intents in the same transaction as removal. */
    void revokeRecipient(Broadcast broadcast, BroadcastReceiver receiver);

    /** Stops unsent refresh events after cancellation or permanent deletion. */
    void revokeBroadcastDispatches(Long broadcastId);

    /** A completed/submitted recipient must not receive a pending reminder refresh. */
    void revokePendingReminderDispatches(Long broadcastId, Long receiverUserId, Integer noticeGeneration);

    /** Retries durable work left by a crash or transient realtime routing failure. */
    int retryPending();

    /** Attempts one task; exposed so the scheduler and after-commit hook share one path. */
    boolean dispatchTask(Long taskId);
}
