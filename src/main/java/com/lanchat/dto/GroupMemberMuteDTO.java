package com.lanchat.dto;

import lombok.Data;

@Data
public class GroupMemberMuteDTO {
    private Long groupId;
    private Long userId;
    /** 禁言时长（分钟），0表示解除禁言 */
    private Integer muteMinutes;
}
