package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("friend_request")
public class FriendRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fromUserId;
    private Long toUserId;
    private String message;

    /** 状态：0-待处理 1-已同意 2-已拒绝 */
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime handleTime;
}
