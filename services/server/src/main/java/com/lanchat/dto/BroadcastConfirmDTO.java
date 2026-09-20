package com.lanchat.dto;

import lombok.Data;

/** Recipient confirmation. Repeating the same value is idempotent. */
@Data
public class BroadcastConfirmDTO {
    private String status;
}
