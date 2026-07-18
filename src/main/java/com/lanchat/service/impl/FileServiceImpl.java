package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanchat.common.FileContentInspector;
import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FilePreviewGrant;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.dto.StoredFileContent;
import com.lanchat.entity.FileAccessLog;
import com.lanchat.entity.FileMetadata;
import com.lanchat.entity.ChatMessage;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.FileAccessGrantMapper;
import com.lanchat.mapper.FileAccessLogMapper;
import com.lanchat.mapper.FileMetadataMapper;
import com.lanchat.service.FileService;
import com.lanchat.service.ConversationService;
import com.lanchat.service.FileObjectCleanupService;
import com.lanchat.service.storage.FileObjectStorage;
import com.lanchat.service.storage.FileObjectStorageRegistry;
import com.lanchat.service.storage.LocalFileObjectStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    /** 缩略图尺寸 */
    private static final int THUMBNAIL_MAX_EDGE = 960;

    /** JPEG 缩略图编码质量 */
    private static final float THUMBNAIL_JPEG_QUALITY = 0.90f;

    /** 预览URL有效期：10分钟 */
    private static final long PREVIEW_URL_EXPIRE_MINUTES = 10;

    /** 同一用户/文件在令牌有效期内复用同一个签名 URL，避免产生无界临时凭证。 */
    private static final String PREVIEW_URL_INDEX_PREFIX = "preview-url:";

    /** 图片文件后缀集合 */
    private static final Set<String> IMAGE_SUFFIXES = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private FileAccessGrantMapper fileAccessGrantMapper;

    @Autowired
    private FileAccessLogMapper fileAccessLogMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private com.lanchat.mapper.UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private FileObjectStorageRegistry storageRegistry;

    @Autowired(required = false)
    private FileObjectCleanupService objectCleanupService;

    @Value("${file.path}")
    private String filePath;

    @Value("${file.staging-path:}")
    private String stagingPath;

    @Value("${file.allowed-types}")
    private String allowedTypes;

    @Value("${file.max-size}")
    private long maxFileSize;

    @Value("${file.avatar-max-size:5242880}")
    private long maxAvatarSize;

    @Value("${file.min-free-space:52428800}")
    private long minimumFreeSpace;

    @Value("${file.max-image-pixels:40000000}")
    private long maxImagePixels;

    @Override
    public FileUploadVO checkFile(FileCheckDTO dto, Long userId) {
        if (dto == null) throw new IllegalArgumentException("文件参数不能为空");
        FileMetadata existing = getByHash(dto.getFileHash());
        // 知道文件哈希不等于拥有文件。只有上传者或已有会话参与者可以秒传引用，
        // 防止通过哈希探测并取得其他组织成员的私有文件。
        if (existing != null && canAccessFile(existing.getFilePath(), userId)) {
            FileUploadVO vo = buildVOFromMetadata(existing);
            vo.setInstantUpload(true);
            return vo;
        }
        return null;
    }

    @Override
    @Transactional
    public FileUploadVO uploadFile(MultipartFile file, Long userId) {
        return uploadValidated(file, userId, false);
    }

    @Override
    @Transactional
    public FileUploadVO uploadAvatar(MultipartFile file, Long userId) {
        return uploadValidated(file, userId, true);
    }

    @Override
    @Transactional
    public FileUploadVO uploadBroadcastImage(MultipartFile file, Long userId) {
        return uploadValidated(file, userId, true);
    }

    private FileUploadVO uploadValidated(MultipartFile file, Long userId, boolean imageOnly) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        if (userId == null) throw new IllegalArgumentException("上传用户无效");

        String originalFilename = sanitizeOriginalFilename(file.getOriginalFilename());
        if (!isAllowedType(originalFilename)) {
            throw new IllegalArgumentException("不支持的文件类型");
        }

        long effectiveLimit = imageOnly ? Math.min(maxFileSize, maxAvatarSize) : maxFileSize;
        if (file.getSize() <= 0 || file.getSize() > effectiveLimit) {
            throw new IllegalArgumentException(imageOnly
                    ? "头像图片不能超过 " + readableMegabytes(effectiveLimit)
                    : "文件大小超过限制（" + readableMegabytes(effectiveLimit) + "）");
        }

        Path root = ensureStagingReady(file.getSize());
        StagedUpload staged = stageUpload(file, root, effectiveLimit);
        try {
            return storeStagedFile(staged.path(), originalFilename, file.getContentType(),
                    staged.size(), staged.sha256(), userId, imageOnly);
        } finally {
            deleteQuietly(staged.path());
        }
    }

    @Override
    @Transactional
    public FileUploadVO storeStagedFile(Path stagedFile,
                                        String originalFilename,
                                        String declaredContentType,
                                        long expectedSize,
                                        String expectedSha256,
                                        Long userId,
                                        boolean imageOnly) {
        if (userId == null || stagedFile == null || !Files.isRegularFile(stagedFile)) {
            throw new IllegalArgumentException("上传文件无效");
        }
        String safeName = sanitizeOriginalFilename(originalFilename);
        if (!isAllowedType(safeName)) throw new IllegalArgumentException("不支持的文件类型");
        long effectiveLimit = imageOnly ? Math.min(maxFileSize, maxAvatarSize) : maxFileSize;
        long actualSize;
        try {
            actualSize = Files.size(stagedFile);
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件内容无法读取");
        }
        if (actualSize <= 0 || actualSize != expectedSize || actualSize > effectiveLimit) {
            throw new IllegalArgumentException("文件大小校验失败");
        }

        String actualSha256 = sha256(stagedFile);
        if (expectedSha256 == null
                || !expectedSha256.matches("(?i)^[0-9a-f]{64}$")
                || !MessageDigest.isEqual(actualSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        expectedSha256.toLowerCase(Locale.ROOT)
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("文件哈希校验失败");
        }

        FileContentInspector.Inspection inspection = FileContentInspector.inspect(
                stagedFile, safeName, declaredContentType, maxImagePixels);
        if (imageOnly && !inspection.image()) {
            throw new IllegalArgumentException("头像必须是有效图片文件");
        }

        // Full bytes were received and verified, therefore duplicate ownership can
        // now be granted without allowing hash-only probing.
        FileMetadata existing = getByHash(actualSha256);
        if (existing != null) {
            fileAccessGrantMapper.grant(existing.getId(), userId, "UPLOAD_PROOF");
            FileUploadVO vo = buildVOFromMetadata(existing);
            vo.setOriginalName(safeName);
            vo.setInstantUpload(true);
            return vo;
        }

        String suffix = "." + inspection.extension();
        String newFileName = UUID.randomUUID().toString().replace("-", "") + suffix;
        FileObjectStorage storage = activeStorage();
        storage.put(newFileName, stagedFile, inspection.mediaType());
        deleteObjectOnTransactionRollback(storage, newFileName,
                inspection.image() ? "thumb_" + newFileName : null);

        FileMetadata metadata = new FileMetadata();
        metadata.setFileHash(actualSha256);
        metadata.setFileName(safeName);
        metadata.setFilePath(newFileName);
        metadata.setFileSize(actualSize);
        metadata.setFileType(inspection.mediaType());
        metadata.setFileSuffix(suffix);
        metadata.setStorageType(storage.type());
        metadata.setUploadUserId(userId);
        metadata.setCreateTime(LocalDateTime.now());
        try {
            fileMetadataMapper.insert(metadata);
        } catch (DuplicateKeyException duplicate) {
            enqueueCleanup(storage, newFileName, "DUPLICATE_UPLOAD");
            FileMetadata raced = getByHash(actualSha256);
            if (raced == null) throw duplicate;
            fileAccessGrantMapper.grant(raced.getId(), userId, "UPLOAD_PROOF");
            FileUploadVO vo = buildVOFromMetadata(raced);
            vo.setOriginalName(safeName);
            vo.setInstantUpload(true);
            return vo;
        }
        fileAccessGrantMapper.grant(metadata.getId(), userId, "UPLOADER");

        FileUploadVO vo = buildVOFromMetadata(metadata);
        vo.setOriginalName(safeName);
        vo.setInstantUpload(false);
        if (inspection.image()) vo.setThumbnailUrl(generateThumbnail(newFileName));
        return vo;
    }

    @Override
    public FileMetadata getByHash(String fileHash) {
        if (fileHash == null || !fileHash.matches("(?i)^[0-9a-f]{64}$")) return null;
        LambdaQueryWrapper<FileMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileMetadata::getFileHash, fileHash);
        return fileMetadataMapper.selectOne(wrapper);
    }

    @Override
    public FileMetadata getByStoredName(String fileName) {
        String normalized = normalizeStoredName(fileName);
        if (normalized == null) return null;
        String metadataName = normalized.startsWith("thumb_") ? normalized.substring(6) : normalized;
        LambdaQueryWrapper<FileMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileMetadata::getFilePath, metadataName);
        return fileMetadataMapper.selectOne(wrapper);
    }

    @Override
    public boolean canAccessFile(String fileName, Long userId) {
        if (userId == null) return false;
        FileMetadata metadata = getByStoredName(fileName);
        if (metadata == null) return false;
        if (userId.equals(metadata.getUploadUserId())) return true;
        if (fileAccessGrantMapper.selectCount(new LambdaQueryWrapper<com.lanchat.entity.FileAccessGrant>()
                .eq(com.lanchat.entity.FileAccessGrant::getFileId, metadata.getId())
                .eq(com.lanchat.entity.FileAccessGrant::getUserId, userId)) > 0) return true;

        // 如果文件被任何用户用作头像，允许所有已登录用户访问
        String fileUrl = getFileUrl(metadata.getFilePath());
        LambdaQueryWrapper<com.lanchat.entity.User> avatarWrapper = new LambdaQueryWrapper<>();
        avatarWrapper.eq(com.lanchat.entity.User::getAvatar, fileUrl);
        if (userMapper.selectCount(avatarWrapper) > 0) return true;
        // 也检查缩略图路径
        String thumbUrl = getFileUrl("thumb_" + metadata.getFilePath());
        LambdaQueryWrapper<com.lanchat.entity.User> thumbAvatarWrapper = new LambdaQueryWrapper<>();
        thumbAvatarWrapper.eq(com.lanchat.entity.User::getAvatar, thumbUrl);
        if (userMapper.selectCount(thumbAvatarWrapper) > 0) return true;

        String storedName = metadata.getFilePath();
        List<ChatMessage> references = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getFilePath, storedName)
                        .eq(ChatMessage::getIsRecalled, 0));
        return references.stream().anyMatch(message ->
                conversationService.canDownloadFile(message.getConversationId(), userId));
    }

    @Override
    public String getFileUrl(String fileName) {
        String normalized = normalizeStoredName(fileName);
        if (normalized == null) throw new IllegalArgumentException("文件名无效");
        return "/api/v1/file/content/" + normalized;
    }

    @Override
    public boolean isAllowedType(String fileName) {
        String suffix = FileContentInspector.extensionOf(fileName);
        if (suffix == null) return false;
        Set<String> allowed = new HashSet<>();
        Arrays.stream(allowedTypes.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .forEach(allowed::add);
        return allowed.contains(suffix);
    }

    /**
     * 生成临时签名文件 URL。签名本身是短期 Bearer 凭证，生成前已完成
     * 当前用户的文件访问校验，浏览器随后可直接流式预览或下载。
     */
    @Override
    public String generatePreviewUrl(String fileName, Long userId) {
        String normalized = normalizeStoredName(fileName);
        if (normalized == null || !canAccessFile(normalized, userId)) {
            throw new IllegalArgumentException("文件不存在或无权访问");
        }

        String indexKey = PREVIEW_URL_INDEX_PREFIX + userId + ":" + normalized;
        String existingToken = redisTemplate.opsForValue().get(indexKey);
        if (existingToken != null && existingToken.matches("(?i)^[0-9a-f]{32}$")) {
            String existingValue = redisTemplate.opsForValue().get("preview:" + existingToken);
            if ((normalized + ":" + userId).equals(existingValue)) {
                return "/api/v1/file/preview/" + existingToken + "/" + normalized;
            }
            redisTemplate.delete(indexKey);
        }

        // 生成签名 token 并存入 Redis，10分钟后自动过期
        String signToken = UUID.randomUUID().toString().replace("-", "");
        String redisKey = "preview:" + signToken;
        redisTemplate.opsForValue().set(redisKey, normalized + ":" + userId,
                PREVIEW_URL_EXPIRE_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(indexKey, signToken,
                PREVIEW_URL_EXPIRE_MINUTES, TimeUnit.MINUTES);
        return "/api/v1/file/preview/" + signToken + "/" + normalized;
    }

    /**
     * 校验签名并获取文件名。签名由 122 位以上随机 UUID 生成，且仅保留 10 分钟。
     */
    @Override
    public FilePreviewGrant resolvePreviewToken(String signToken) {
        if (signToken == null || !signToken.matches("(?i)^[0-9a-f]{32}$")) return null;
        String redisKey = "preview:" + signToken;
        String value = redisTemplate.opsForValue().get(redisKey);
        if (value == null) return null;
        int separator = value.lastIndexOf(':');
        if (separator <= 0) return null;
        try {
            String fileName = value.substring(0, separator);
            Long userId = Long.parseLong(value.substring(separator + 1));
            return normalizeStoredName(fileName) == null ? null : new FilePreviewGrant(fileName, userId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public void recordAccess(String fileName,
                             Long userId,
                             String action,
                             String result,
                             String requestId,
                             String clientAddress) {
        FileMetadata metadata = getByStoredName(fileName);
        if (metadata == null || userId == null) return;

        FileAccessLog accessLog = new FileAccessLog();
        accessLog.setFileId(metadata.getId());
        accessLog.setUserId(userId);
        accessLog.setAction(safeAuditValue(action, 20, "ACCESS"));
        accessLog.setResult(safeAuditValue(result, 20, "UNKNOWN"));
        accessLog.setRequestId(safeAuditValue(requestId, 80, null));
        accessLog.setClientAddress(safeAuditValue(clientAddress, 64, null));
        accessLog.setCreateTime(LocalDateTime.now());
        fileAccessLogMapper.insert(accessLog);
    }

    /**
     * 生成图片缩略图（300x300）
     * PRD: 图片自动生成缩略图（300x300），用于卡片展示
     */
    @Override
    public String generateThumbnail(String fileName) {
        Path temporary = null;
        try {
            FileMetadata metadata = getByStoredName(fileName);
            if (metadata == null) return null;
            FileObjectStorage storage = storageFor(metadata);
            String normalized = normalizeStoredName(fileName);
            if (normalized == null || !storage.exists(normalized)) return null;

            BufferedImage sourceImage;
            try (InputStream input = storage.open(normalized)) {
                sourceImage = ImageIO.read(input);
            }
            if (sourceImage == null) return null;

            int sourceWidth = sourceImage.getWidth();
            int sourceHeight = sourceImage.getHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) return  null;

            double scale = Math.min(
                    1.0,
                    (double) THUMBNAIL_MAX_EDGE
                            / Math.max(sourceWidth, sourceHeight)
            );

            int targetWidth = Math.max(
                    1,
                    (int) Math.round(sourceWidth * scale)
            );

            int targetHeight = Math.max(
                    1,
                    (int) Math.round(sourceHeight * scale)
            );

            String suffix = normalized
                    .substring(normalized.lastIndexOf('.') + 1)
                    .toLowerCase(Locale.ROOT);

            boolean preserveAlpha = sourceImage.getColorModel().hasAlpha()
                    && ("png".equals(suffix) || "gif".equals(suffix));

            BufferedImage thumbnail = new BufferedImage(
                    targetWidth,
                    targetHeight,
                    preserveAlpha
                            ? BufferedImage.TYPE_INT_ARGB
                            : BufferedImage.TYPE_INT_RGB
            );

            Graphics2D graphics = thumbnail.createGraphics();

            try {
                // JPEG 不支持透明背景，使用白色填充，避免透明区域变黑。
                if (!preserveAlpha) {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, targetWidth, targetHeight);
                }

                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC
                );

                graphics.setRenderingHint(
                        RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY
                );

                graphics.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );

                graphics.setRenderingHint(
                        RenderingHints.KEY_COLOR_RENDERING,
                        RenderingHints.VALUE_COLOR_RENDER_QUALITY
                );

                graphics.drawImage(
                        sourceImage,
                        0,
                        0,
                        targetWidth,
                        targetHeight,
                        null
                );
            } finally {
                graphics.dispose();
            }

            String thumbName = "thumb_" + normalized;

            temporary = Files.createTempFile(
                    ensureStagingReady(0),
                    ".thumbnail-",
                    ".tmp"
            );

            if (!writeThumbnail(thumbnail, suffix, temporary)) {
                // 例如运行环境只有 WebP reader，没有对应 writer。
                return null;
            }

            storage.put(
                    thumbName,
                    temporary,
                    metadata.getFileType()
            );

            return getFileUrl(thumbName);
        } catch (IOException exception) {
            log.warn(
                    "生成缩略图失败，fileName={}, reason={}",
                    fileName,
                    exception.getMessage()
            );

            return null;
        } finally {
            deleteQuietly(temporary);
        }
    }

    private boolean writeThumbnail(
            BufferedImage image,
            String suffix,
            Path target
    ) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(suffix);
        if (!writers.hasNext()) return false;
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            if (output == null) return false;
            writer .setOutput(output);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            boolean jpeg = "jpg".equalsIgnoreCase(suffix) || "jpeg".equalsIgnoreCase(suffix);
            if (jpeg && writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(THUMBNAIL_JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), writeParam);
            output.flush();
            return true;
        } finally {
            writer.dispose();
        }
    }

    @Override
    public StoredFileContent openContent(String fileName) {
        String normalized = normalizeStoredName(fileName);
        FileMetadata metadata = normalized == null ? null : getByStoredName(normalized);
        if (metadata == null) return null;
        FileObjectStorage storage = storageFor(metadata);
        if (!storage.exists(normalized)) return null;
        return new StoredFileContent(
                new InputStreamResource(storage.open(normalized)),
                storage.size(normalized),
                metadata.getFileType(),
                metadata.getFileName());
    }

    @Override
    public void deleteStoredObjects(FileMetadata metadata) {
        if (metadata == null || normalizeStoredName(metadata.getFilePath()) == null) return;
        FileObjectStorage storage = storageFor(metadata);
        enqueueCleanup(storage, metadata.getFilePath(), "FILE_METADATA_REMOVED");
        enqueueCleanup(storage, "thumb_" + metadata.getFilePath(), "FILE_METADATA_REMOVED");
    }

    /**
     * 判断是否为图片类型
     */
    public boolean isImageType(String fileName) {
        if (fileName == null || !fileName.contains(".")) return false;
        String suffix = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        return IMAGE_SUFFIXES.contains(suffix);
    }

    /**
     * 从文件元数据构建返回VO
     */
    private FileUploadVO buildVOFromMetadata(FileMetadata metadata) {
        FileUploadVO vo = new FileUploadVO();
        vo.setId(metadata.getId());
        vo.setUrl(getFileUrl(metadata.getFilePath()));
        vo.setOriginalName(metadata.getFileName());
        vo.setFileName(metadata.getFilePath());
        vo.setFileSize(metadata.getFileSize());
        vo.setFileType(metadata.getFileType());
        vo.setFileHash(metadata.getFileHash());

        // 如果是图片，检查缩略图是否存在
        String suffix = metadata.getFileSuffix() != null ? metadata.getFileSuffix().replace(".", "").toLowerCase() : "";
        if (IMAGE_SUFFIXES.contains(suffix)) {
            String thumbName = "thumb_" + metadata.getFilePath();
            FileObjectStorage storage = storageFor(metadata);
            if (storage.exists(thumbName)) {
                vo.setThumbnailUrl(getFileUrl(thumbName));
            } else {
                vo.setThumbnailUrl(generateThumbnail(metadata.getFilePath()));
            }
        }

        return vo;
    }

    private Path getStorageRoot() {
        return Paths.get(filePath).toAbsolutePath().normalize();
    }

    private String normalizeStoredName(String fileName) {
        if (fileName == null) return null;
        String value = fileName.trim();
        if (!value.matches("^(?:thumb_)?[0-9a-fA-F]{32}\\.[a-zA-Z0-9]{1,10}$")) return null;
        return value;
    }

    private Path ensureStagingReady(long incomingSize) {
        Path root = getStagingRoot();
        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        Path sourceStatic = workingDirectory.resolve("src/main/resources/static").normalize();
        Path compiledStatic = workingDirectory.resolve("target/classes/static").normalize();
        if (root.startsWith(sourceStatic) || root.startsWith(compiledStatic)) {
            throw new IllegalStateException("文件存储目录不得位于静态资源目录中");
        }

        try {
            Files.createDirectories(root);
            if (!Files.isDirectory(root) || !Files.isWritable(root)) {
                throw new IllegalStateException("文件存储目录不可写");
            }
            FileStore store = Files.getFileStore(root);
            if (store.getUsableSpace() < Math.max(0, incomingSize) + Math.max(0, minimumFreeSpace)) {
                throw new IllegalArgumentException("存储空间不足，请联系节点管理员");
            }
            return root;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("文件存储目录不可用");
        }
    }

    private Path getStagingRoot() {
        if (StringUtils.hasText(stagingPath)) {
            return Paths.get(stagingPath).toAbsolutePath().normalize();
        }
        if (storageRegistry == null || "LOCAL".equals(storageRegistry.activeType())) {
            return getStorageRoot();
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "lanchat-upload-staging")
                .toAbsolutePath().normalize();
    }

    private FileObjectStorage activeStorage() {
        return storageRegistry == null
                ? new LocalFileObjectStorage(getStorageRoot())
                : storageRegistry.active();
    }

    private FileObjectStorage storageFor(FileMetadata metadata) {
        String type = metadata == null ? "LOCAL" : metadata.getStorageType();
        if (storageRegistry == null) return new LocalFileObjectStorage(getStorageRoot());
        return storageRegistry.forType(type);
    }

    private String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("文件内容无法读取");
        }
    }

    private void deleteObjectOnTransactionRollback(FileObjectStorage storage, String... keys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return;
                for (String key : keys) enqueueDetachedCleanup(storage, key, "UPLOAD_ROLLBACK");
            }
        });
    }

    private void enqueueDetachedCleanup(FileObjectStorage storage, String key, String reason) {
        if (storage == null || key == null || objectCleanupService == null) return;
        objectCleanupService.enqueueDetached(storage.type(), key, reason);
    }

    private void enqueueCleanup(FileObjectStorage storage, String key, String reason) {
        if (storage == null || key == null) return;
        if (objectCleanupService != null) {
            objectCleanupService.enqueue(storage.type(), key, reason);
        } else {
            // Never bypass the durable outbox with an early physical delete.
            log.error("文件对象清理服务不可用，未执行非持久化删除: {}", key);
        }
    }

    private StagedUpload stageUpload(MultipartFile file, Path root, long limit) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = file.getInputStream();
                 OutputStream output = Files.newOutputStream(temporary,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    if (total > limit) throw new IllegalArgumentException("文件大小超过限制");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (total <= 0) throw new IllegalArgumentException("文件不能为空");
            return new StagedUpload(temporary, HexFormat.of().formatHex(digest.digest()), total);
        } catch (IllegalArgumentException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("文件上传写入失败");
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("临时文件清理失败: {}", path.getFileName());
        }
    }

    private String sanitizeOriginalFilename(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) throw new IllegalArgumentException("文件名不能为空");
        String normalized = originalFilename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        normalized = normalized.replaceAll("[\\p{Cntrl}]", "");
        if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")) {
            throw new IllegalArgumentException("文件名无效");
        }
        if (normalized.length() > 180) {
            String extension = FileContentInspector.extensionOf(normalized);
            if (extension == null) throw new IllegalArgumentException("文件名过长");
            normalized = normalized.substring(0, Math.min(160, normalized.lastIndexOf('.')))
                    + "." + extension;
        }
        return normalized;
    }

    private String readableMegabytes(long bytes) {
        return Math.max(1, bytes / 1024 / 1024) + "MB";
    }

    private String safeAuditValue(String value, int maximumLength, String fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        String sanitized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return sanitized.substring(0, Math.min(maximumLength, sanitized.length()));
    }

    private record StagedUpload(Path path, String sha256, long size) {
    }
}
