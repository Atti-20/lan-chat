package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("broadcast_evidence")
public class BroadcastEvidence {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long broadcastId;

    /**
     * 广播正文附件为空；
     * 接收者完成证据保存 broadcast_receiver.id。
     */
    private Long receiverId;

    private Long userId;

    /**
     * CONTENT_IMAGE
     * CONTENT_LOCATION
     * COMPLETION_IMAGE
     * COMPLETION_LOCATION
     */
    private String evidenceType;

    private Long fileId;

    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal accuracyMeters;
    private String addressText;
    private LocalDateTime capturedAt;

    private LocalDateTime createTime;
}
