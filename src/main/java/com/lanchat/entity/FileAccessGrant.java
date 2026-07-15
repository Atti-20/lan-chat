package com.lanchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户对物理文件对象的显式访问授权。 */
@Data
@TableName("file_access_grant")
public class FileAccessGrant {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fileId;
    private Long userId;
    private String grantType;
    private LocalDateTime createTime;
}
