package com.lanchat.dto;

import lombok.Data;

@Data
public class FriendRequestDTO {
    /** 被申请者用户ID */
    private Long toUserId;
    /** 验证信息 */
    private String message;
}
