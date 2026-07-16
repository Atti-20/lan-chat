package com.lanchat.dto;

import lombok.Data;

@Data
public class ResumableUploadInitDTO {
    private String clientUploadId;
    private String conversationId;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String fileHash;
}
