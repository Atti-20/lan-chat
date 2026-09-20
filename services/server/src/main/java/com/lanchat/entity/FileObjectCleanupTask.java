package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_object_cleanup_task")
public class FileObjectCleanupTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String storageType;
    private String objectKey;
    private String reason;
    private String taskType;
    private String uploadId;
    private Integer partNumber;
    private Integer attempts;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
