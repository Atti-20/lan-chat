package com.lanchat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables lifecycle cleanup even when file transfer is used without other scheduled modules. */
@Configuration
@EnableScheduling
public class FileTransferSchedulingConfig {
}
