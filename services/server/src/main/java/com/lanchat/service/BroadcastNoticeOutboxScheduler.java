package com.lanchat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries only committed broadcast notice tasks; it never constructs a card itself. */
@Component
public class BroadcastNoticeOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(BroadcastNoticeOutboxScheduler.class);
    private final BroadcastNoticeOutboxService outboxService;

    public BroadcastNoticeOutboxScheduler(BroadcastNoticeOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(
            fixedDelayString = "${broadcast.notice-outbox.fixed-delay-ms:15000}",
            initialDelayString = "${broadcast.notice-outbox.initial-delay-ms:15000}"
    )
    public void retryPendingTasks() {
        try {
            int dispatched = outboxService.retryPending();
            if (dispatched > 0) log.debug("已重试 {} 个广播通知投递任务", dispatched);
        } catch (RuntimeException exception) {
            log.warn("广播通知持久化投递扫描失败: {}", exception.getMessage());
        }
    }
}
