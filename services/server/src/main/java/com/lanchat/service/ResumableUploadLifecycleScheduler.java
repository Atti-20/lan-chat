package com.lanchat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResumableUploadLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResumableUploadLifecycleScheduler.class);
    private final ResumableUploadService resumableUploadService;

    public ResumableUploadLifecycleScheduler(ResumableUploadService resumableUploadService) {
        this.resumableUploadService = resumableUploadService;
    }

    @Scheduled(
            fixedDelayString = "${file.upload.cleanup-fixed-delay-ms:600000}",
            initialDelayString = "${file.upload.cleanup-initial-delay-ms:60000}"
    )
    public void expireUploads() {
        try {
            int expired = resumableUploadService.expireSessions();
            if (expired > 0) log.info("Expired {} resumable upload sessions", expired);
        } catch (Exception exception) {
            log.error("Resumable upload lifecycle scan failed", exception);
        }
    }
}
