package com.lanchat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FileTransferLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(FileTransferLifecycleScheduler.class);

    private final FileTransferService fileTransferService;

    public FileTransferLifecycleScheduler(FileTransferService fileTransferService) {
        this.fileTransferService = fileTransferService;
    }

    @Scheduled(
            fixedDelayString = "${file.transfer.lifecycle-fixed-delay-ms:60000}",
            initialDelayString = "${file.transfer.lifecycle-initial-delay-ms:60000}"
    )
    public void expirePendingTransfers() {
        try {
            int expired = fileTransferService.expirePendingTransfers();
            if (expired > 0) log.info("Expired {} unfinished file transfer tasks", expired);
        } catch (Exception exception) {
            // A temporary database failure must not cancel future scheduled scans.
            log.error("File transfer lifecycle scan failed", exception);
        }
    }
}
