package com.lanchat.dto;

import lombok.Data;

/**
 * Explicit acknowledgement required before the irreversible account-erasure path.
 */
@Data
public class AdminPhysicalErasureDTO {
    private String confirmationPhrase;
    private String reason;
}
