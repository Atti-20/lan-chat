package com.lanchat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TemporaryRoomLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(TemporaryRoomLifecycleScheduler.class);

    private final TemporaryRoomService roomService;

    public TemporaryRoomLifecycleScheduler(TemporaryRoomService roomService) {
        this.roomService = roomService;
    }

    @Scheduled(
            fixedDelayString = "${lanchat.rooms.lifecycle-interval-ms:60000}",
            initialDelayString = "${lanchat.rooms.lifecycle-initial-delay-ms:60000}"
    )
    public void processExpiredRooms() {
        try {
            roomService.processExpiredRooms(LocalDateTime.now());
        } catch (Exception exception) {
            log.error("Temporary room lifecycle scan failed", exception);
        }
    }
}
