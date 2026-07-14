package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.entity.FileMetadata;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
     * 认证文件内容接口。文件不再通过 /file/** 作为公开静态资源暴露。
     */
    @GetMapping("/content/{fileName:.+}")
    public ResponseEntity<Resource> getFileContent(@PathVariable String fileName) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!fileService.canAccessFile(fileName, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return buildFileResponse(fileName);
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

        if (!fileService.canAccessFile(fileName, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return buildFileResponse(fileName);
    }

    private ResponseEntity<Resource> buildFileResponse(String fileName) {
        FileMetadata metadata = fileService.getByStoredName(fileName);
        if (metadata == null) return ResponseEntity.notFound().build();

        Path root = Paths.get(filePath).toAbsolutePath().normalize();
        Path resolved = root.resolve(fileName).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return ResponseEntity.notFound().build();
        }

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            String detected = Files.probeContentType(resolved);
            if (detected != null) mediaType = MediaType.parseMediaType(detected);
        } catch (Exception ignored) {
            // 未识别类型时按二进制流返回。
        }

        FileSystemResource resource = new FileSystemResource(resolved);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(metadata.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }
}
