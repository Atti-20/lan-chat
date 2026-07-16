package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.common.RequestIdFilter;
import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FilePreviewGrant;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.entity.FileMetadata;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.FileService;
import com.lanchat.service.ConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/file")
@CrossOrigin
public class FileController {

    @Autowired
    private FileService fileService;

    @Autowired
    private ConversationService conversationService;

    @Value("${file.path}")
    private String filePath;

    @PostMapping("/check")
    public Result<FileUploadVO> checkFile(@RequestBody FileCheckDTO dto) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) return Result.unauthorized("请先登录");
        if (dto == null || !conversationService.canUploadFile(dto.getConversationId(), userId)) {
            return Result.forbidden("无权在该会话发送文件");
        }
        FileUploadVO vo = fileService.checkFile(dto, userId);
        return Result.success(vo);
    }

    @PostMapping("/upload")
    public Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam String conversationId) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("请先登录");
        }
        if (!conversationService.canUploadFile(conversationId, userId)) {
            return Result.forbidden("无权在该会话发送文件");
        }
        return Result.success(fileService.uploadFile(file, userId));
    }

    /** 头像使用独立入口，避免聊天附件绕过会话发送权限。 */
    @PostMapping("/avatar")
    public Result<FileUploadVO> uploadAvatar(@RequestParam("file") MultipartFile file) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) return Result.unauthorized("请先登录");
        if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            return Result.error(400, "头像必须是图片文件");
        }
        return Result.success(fileService.uploadAvatar(file, userId));
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
        try {
            String previewUrl = fileService.generatePreviewUrl(fileName, userId);
            recordAccess(fileName, userId, "PREVIEW_URL", "ALLOWED");
            return Result.success(previewUrl);
        } catch (IllegalArgumentException exception) {
            recordAccess(fileName, userId, "PREVIEW_URL", "DENIED");
            throw exception;
        }
    }

    /**
     * 认证文件内容接口。文件不再通过 /file/** 作为公开静态资源暴露。
     */
    @GetMapping("/content/{fileName:.+}")
    public ResponseEntity<Resource> getFileContent(@PathVariable String fileName) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!fileService.canAccessFile(fileName, userId)) {
            recordAccess(fileName, userId, "CONTENT", "DENIED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        recordAccess(fileName, userId, "CONTENT", "ALLOWED");
        return buildFileResponse(fileName, false, false);
    }

    /**
     * 通过短期签名直接流式访问文件。签名生成前已经校验用户权限，
     * 此处无需 Authorization，才能供 img 和浏览器原生下载直接使用。
     */
    @GetMapping("/preview/{signToken}")
    public ResponseEntity<Resource> previewFile(@PathVariable String signToken,
                                                @RequestParam(defaultValue = "false") boolean download) {
        return streamPreview(signToken, null, download);
    }

    /**
     * The display name is intentionally cosmetic. The signed token remains the
     * source of truth; the cosmetic extension only helps browsers choose a
     * suitable native preview. Private responses are never shared-cacheable.
     */
    @GetMapping("/preview/{signToken}/{displayName:.+}")
    public ResponseEntity<Resource> previewFileWithDisplayName(@PathVariable String signToken,
                                                               @PathVariable String displayName,
                                                               @RequestParam(defaultValue = "false") boolean download) {
        return streamPreview(signToken, displayName, download);
    }

    private ResponseEntity<Resource> streamPreview(String signToken, String displayName, boolean download) {
        FilePreviewGrant grant = fileService.resolvePreviewToken(signToken);
        if (grant == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String fileName = grant.fileName();
        if (displayName != null && !displayName.equals(fileName)) {
            recordAccess(fileName, grant.userId(), download ? "DOWNLOAD" : "PREVIEW", "DENIED");
            return ResponseEntity.notFound().build();
        }
        if (!fileService.canAccessFile(fileName, grant.userId())) {
            recordAccess(fileName, grant.userId(), download ? "DOWNLOAD" : "PREVIEW", "REVOKED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        recordAccess(fileName, grant.userId(), download ? "DOWNLOAD" : "PREVIEW", "ALLOWED");
        return buildFileResponse(fileName, download, true);
    }

    private ResponseEntity<Resource> buildFileResponse(String fileName, boolean download, boolean signedPreview) {
        FileMetadata metadata = fileService.getByStoredName(fileName);
        if (metadata == null) return ResponseEntity.notFound().build();

        Path root = Paths.get(filePath).toAbsolutePath().normalize();
        Path resolved = root.resolve(fileName).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return ResponseEntity.notFound().build();
        }

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            String detected = metadata.getFileType();
            if (detected == null || detected.isBlank()) detected = Files.probeContentType(resolved);
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
        boolean inlineAllowed = !download && isSafeInlineType(mediaType);
        if (!inlineAllowed) mediaType = MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition disposition = (inlineAllowed ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(metadata.getFileName(), StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("X-Frame-Options", "SAMEORIGIN");
        if (signedPreview) {
            // A signed URL is still a bearer credential. Shared/CDN caches must not
            // retain private bytes after the token or conversation grant expires.
            response.cacheControl(CacheControl.noStore().cachePrivate());
        } else {
            // Authorization-header responses must remain private.
            response.cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate());
        }
        return response.body(resource);
    }

    private boolean isSafeInlineType(MediaType mediaType) {
        if (mediaType == null) return false;
        if (MediaType.APPLICATION_PDF.includes(mediaType)) return true;
        if (MediaType.TEXT_PLAIN.includes(mediaType)) return true;
        String type = mediaType.getType();
        String subtype = mediaType.getSubtype().toLowerCase();
        if ("image".equals(type)) {
            return Set.of("jpeg", "png", "gif", "bmp", "webp").contains(subtype);
        }
        return "audio".equals(type) || "video".equals(type);
    }

    private void recordAccess(String fileName, Long userId, String action, String result) {
        ServletRequestAttributes attributes = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes value
                ? value : null;
        String requestId = attributes == null ? null
                : String.valueOf(attributes.getRequest().getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        String remoteAddress = attributes == null ? null : attributes.getRequest().getRemoteAddr();
        fileService.recordAccess(fileName, userId, action, result,
                "null".equals(requestId) ? null : requestId, remoteAddress);
    }
}
