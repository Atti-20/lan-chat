package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@RestController
@RequestMapping("/api/v1/file")
@CrossOrigin
public class FileController {

    @Autowired
    private FileService fileService;

    @Value("${file.path}")
    private String filePath;

    @PostMapping("/check")
    public Result<FileUploadVO> checkFile(@RequestBody FileCheckDTO dto) {
        FileUploadVO vo = fileService.checkFile(dto);
        return Result.success(vo);
    }

    @PostMapping("/upload")
    public Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("请先登录");
        }
        return Result.success(fileService.uploadFile(file, userId));
    }

    /**
     * 生成临时签名预览URL
     * PRD: 预览链接为临时签名URL，有效期10分钟，与用户Token绑定
     */
    @PostMapping("/preview-url")
    public Result<String> generatePreviewUrl(@RequestParam String fileName) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("请先登录");
        }
        return Result.success(fileService.generatePreviewUrl(fileName, userId));
    }

    /**
     * 通过签名访问文件预览
     * 验证签名有效性和用户绑定关系
     */
    @GetMapping("/preview/{signToken}")
    public ResponseEntity<Resource> previewFile(@PathVariable String signToken) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null || !fileService.validatePreviewToken(signToken, userId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String fileName = fileService.getFileNameFromToken(signToken);
        if (fileName == null) {
            return ResponseEntity.notFound().build();
        }

        File file = new File(filePath + fileName);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .body(resource);
    }
}
