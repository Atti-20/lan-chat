package com.lanchat.dto;

/** One recipient in the sender-only read receipt for a group mention. */
public record MentionReadRecipientDTO(
        Long userId,
        String nickname,
        String avatar,
        boolean read
) {
}
