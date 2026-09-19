package com.lanchat.dto;

import lombok.Data;

@Data
public class FileCheckDTO {
    private String conversationId;
    /** 文件哈希值 */
    private String fileHash;
    /** 文件名 */
    private String fileName;
    /** 文件大小 */
    private Long fileSize;
}
