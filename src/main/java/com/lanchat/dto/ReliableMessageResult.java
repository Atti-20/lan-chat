package com.lanchat.dto;

import com.lanchat.entity.ChatMessage;

public record ReliableMessageResult(ChatMessage message, boolean duplicated) {
}
