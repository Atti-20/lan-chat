package com.lanchat.dto;

import java.util.List;

/** Attachments and optional location published with a broadcast. */
public record BroadcastContentEvidenceDTO(List<String> imageUrls, BroadcastLocationDTO location) {
    public BroadcastContentEvidenceDTO {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
    }
}
