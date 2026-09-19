package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable control-plane record for WebRTC file transfer and node-relay fallback.
 * File bytes are never stored in this row.
 */
@Data
@TableName("file_transfer")
public class FileTransfer {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Server-generated public transfer identifier. */
    private String transferId;

    /** Sender-generated idempotency key, unique within the sender account. */
    private String clientTransferId;

    private String conversationId;
    private Long senderUserId;
    private Long senderDeviceId;
    private Long receiverUserId;

    /** Null until the first receiver device atomically claims the offer. */
    private Long receiverDeviceId;

    private String fileName;
    private Long fileSize;
    private String fileType;
    private String fileHash;

    /** Values from {@link FileTransferStatus}. */
    private String status;

    /** Values from {@link FileTransferPath}. */
    private String transportPath;

    /** Populated only after the existing node file service has persisted relay bytes. */
    private Long fileMetadataId;
    private String storedFileName;

    /** A bounded machine-readable reason such as RTC_TIMEOUT; never raw SDP or ICE data. */
    private String fallbackReason;

    /** Deadline for an unfinished negotiation or relay upload; completed rows remain valid. */
    private LocalDateTime expiresAt;
    private LocalDateTime claimedTime;
    private LocalDateTime completedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
