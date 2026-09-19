package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_metadata")
public class FileMetadata {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileHash;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String fileType;
    private String fileSuffix;
    /** LOCAL for historical rows, MINIO for shared object storage. */
    private String storageType;
    private Long uploadUserId;
    private LocalDateTime createTime;
}
