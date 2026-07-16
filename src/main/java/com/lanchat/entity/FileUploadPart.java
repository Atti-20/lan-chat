package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_upload_part")
public class FileUploadPart {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String uploadId;
    private Integer partNumber;
    private Long partSize;
    private String partHash;
    private String storagePath;
    private LocalDateTime createTime;
}
