package com.lanchat.service;

import com.lanchat.dto.FileUploadVO;
import com.lanchat.dto.ResumableUploadInitDTO;
import com.lanchat.dto.ResumableUploadVO;

import java.io.InputStream;

public interface ResumableUploadService {
    ResumableUploadVO initialize(ResumableUploadInitDTO request, Long userId);
    ResumableUploadVO status(String uploadId, Long userId);
    ResumableUploadVO uploadPart(String uploadId,
                                 int partNumber,
                                 String sha256,
                                 long contentLength,
                                 InputStream input,
                                 Long userId);
    FileUploadVO complete(String uploadId, Long userId);
    void cancel(String uploadId, Long userId);
    int expireSessions();
}
