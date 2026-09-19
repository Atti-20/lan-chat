package com.lanchat.dto;

import lombok.Data;

@Data
public class FileUploadVO {
    private Long id;
    private String url;
    private String thumbnailUrl;
    private String previewUrl;
    private String originalName;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String fileHash;
    private Boolean instantUpload;
}
