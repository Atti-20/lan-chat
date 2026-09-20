package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_upload_session")
public class FileUploadSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String uploadId;
    private String clientUploadId;
    private Long userId;
    private String conversationId;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String fileHash;
    private Long chunkSize;
    private Integer totalParts;
    private String status;
    private String storageType;
    private Long completedFileId;
    private LocalDateTime expiresAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
