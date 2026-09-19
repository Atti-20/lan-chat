package com.lanchat.dto;

import org.springframework.core.io.Resource;

/** Open private object plus verified metadata used by HTTP streaming. */
public record StoredFileContent(
        Resource resource,
        long contentLength,
        String mediaType,
        String originalName
) {
}
