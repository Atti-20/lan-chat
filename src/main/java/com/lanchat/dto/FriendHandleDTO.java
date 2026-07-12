package com.lanchat.dto;

import lombok.Data;

@Data
public class FriendHandleDTO {
    private Long requestId;
    /** 是否同意 */
    private Boolean accept;
}
