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
import java.time.Duration;

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
     * 生成临时签名文件 URL。生成前校验当前用户的文件权限，
     * 签名链接只在 10 分钟内有效。
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
        return buildFileResponse(fileName, false);
    }

    /**
     * 通过短期签名直接流式访问文件。签名生成前已经校验用户权限，
     * 此处无需 Authorization，才能供 img 和浏览器原生下载直接使用。
     */
    @GetMapping("/preview/{signToken}")
    public ResponseEntity<Resource> previewFile(@PathVariable String signToken,
                                                @RequestParam(defaultValue = "false") boolean download) {
        String fileName = fileService.getFileNameFromToken(signToken);
        if (fileName == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return buildFileResponse(fileName, download);
    }

    private ResponseEntity<Resource> buildFileResponse(String fileName, boolean download) {
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

        long contentLength;
        try {
            contentLength = Files.size(resolved);
        } catch (Exception ignored) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(resolved);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(metadata.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }
}
