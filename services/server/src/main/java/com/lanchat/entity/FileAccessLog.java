package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Auditable file preview and download decision. */
@Data
@TableName("file_access_log")
public class FileAccessLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fileId;
    private Long userId;
    private String action;
    private String result;
    private String requestId;
    private String clientAddress;
    private LocalDateTime createTime;
}
