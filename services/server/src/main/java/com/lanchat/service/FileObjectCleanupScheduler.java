package com.lanchat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FileObjectCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(FileObjectCleanupScheduler.class);
    private final FileObjectCleanupService cleanupService;

    public FileObjectCleanupScheduler(FileObjectCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(
            fixedDelayString = "${file.cleanup.fixed-delay-ms:60000}",
            initialDelayString = "${file.cleanup.initial-delay-ms:60000}"
    )
    public void retryCleanupTasks() {
        try {
            int cleaned = cleanupService.retryPending();
            if (cleaned > 0) log.info("Retried and completed {} file object cleanup tasks", cleaned);
        } catch (Exception exception) {
            log.error("File object cleanup retry scan failed", exception);
        }
    }
}
