package com.lanchat.control.rbac;

import lombok.Data;

@Data
public class OwnerTransferRequest {
    private Long targetUserId;
    private String confirmationPhrase;
}
