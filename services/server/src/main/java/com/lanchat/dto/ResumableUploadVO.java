package com.lanchat.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ResumableUploadVO(
        String uploadId,
        String status,
        long chunkSize,
        int totalParts,
        /** Successfully persisted part numbers. Part numbering is 1-based. */
        List<Integer> uploadedParts,
        LocalDateTime expiresAt,
        FileUploadVO completedFile
) {
}
