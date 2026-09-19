package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("group_member")
public class GroupMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;
    private Long userId;

    /** 角色：0-普通成员 1-管理员 2-群主 */
    private Integer role;

    /** 禁言截止时间 */
    private LocalDateTime muteUntil;

    private LocalDateTime joinTime;
}
