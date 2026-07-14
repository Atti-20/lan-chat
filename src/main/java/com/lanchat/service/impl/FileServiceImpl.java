package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.entity.FileMetadata;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.GroupMember;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.FileMetadataMapper;
import com.lanchat.mapper.GroupMemberMapper;
import com.lanchat.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    /** 缩略图尺寸 */
    private static final int THUMBNAIL_SIZE = 300;

    /** 预览URL有效期：10分钟 */
    private static final long PREVIEW_URL_EXPIRE_MINUTES = 10;

    /** 同一用户/文件在令牌有效期内复用同一个签名 URL，确保 CDN 缓存键稳定 */
    private static final String PREVIEW_URL_INDEX_PREFIX = "preview-url:";

    /** 图片文件后缀集合 */
    private static final Set<String> IMAGE_SUFFIXES = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private GroupMemberMapper groupMemberMapper;

    @Autowired
    private com.lanchat.mapper.UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${file.path}")
    private String filePath;

    @Value("${file.allowed-types}")
    private String allowedTypes;

    @Value("${file.max-size}")
    private long maxFileSize;

    @Override
    public FileUploadVO checkFile(FileCheckDTO dto) {
        if (dto == null) throw new IllegalArgumentException("文件参数不能为空");
        FileMetadata existing = getByHash(dto.getFileHash());
        if (existing != null) {
            FileUploadVO vo = buildVOFromMetadata(existing);
            vo.setInstantUpload(true);
            return vo;
        }
        return null;
    }

    @Override
    public FileUploadVO uploadFile(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (!isAllowedType(originalFilename)) {
            throw new IllegalArgumentException("不支持的文件类型");
        }

        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("文件大小超过限制（100MB）");
        }

        String fileHash = calculateSha256(file);

        // 秒传检测
        FileMetadata existing = getByHash(fileHash);
        if (existing != null) {
            FileUploadVO vo = buildVOFromMetadata(existing);
            vo.setOriginalName(originalFilename);
            vo.setInstantUpload(true);
            return vo;
        }

        // 获取文件后缀
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }
        String newFileName = UUID.randomUUID().toString().replace("-", "") + suffix;

        // 确保目录存在
        File destDir = getStorageRoot().toFile();
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        // 保存文件
        File destFile = resolveStoredFile(newFileName);
        try {
            file.transferTo(destFile);
        } catch (IOException e) {
            log.error("文件写入失败", e);
            throw new RuntimeException("文件上传失败");
        }

        // 保存文件元数据
        FileMetadata metadata = new FileMetadata();
        metadata.setFileHash(fileHash);
        metadata.setFileName(originalFilename);
        metadata.setFilePath(newFileName);
        metadata.setFileSize(file.getSize());
        metadata.setFileType(file.getContentType());
        metadata.setFileSuffix(suffix);
        metadata.setUploadUserId(userId);
        metadata.setCreateTime(LocalDateTime.now());
        fileMetadataMapper.insert(metadata);

        // 构建返回结果
        FileUploadVO vo = new FileUploadVO();
        vo.setUrl(getFileUrl(newFileName));
        vo.setOriginalName(originalFilename);
        vo.setFileName(newFileName);
        vo.setFileSize(file.getSize());
        vo.setFileType(file.getContentType());
        vo.setFileHash(fileHash);
        vo.setInstantUpload(false);

        // 图片文件自动生成缩略图
        String lowerSuffix = suffix.replace(".", "").toLowerCase();
        if (IMAGE_SUFFIXES.contains(lowerSuffix)) {
            String thumbnailUrl = generateThumbnail(newFileName);
            vo.setThumbnailUrl(thumbnailUrl);
        }

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
        LambdaQueryWrapper<ChatMessage> privateWrapper = new LambdaQueryWrapper<>();
        privateWrapper.eq(ChatMessage::getFilePath, storedName)
                .and(w -> w.eq(ChatMessage::getFromUserId, userId)
                        .or()
                        .eq(ChatMessage::getToUserId, userId));
        if (chatMessageMapper.selectCount(privateWrapper) > 0) return true;

        LambdaQueryWrapper<GroupMember> membershipWrapper = new LambdaQueryWrapper<>();
        membershipWrapper.eq(GroupMember::getUserId, userId);
        List<GroupMember> memberships = groupMemberMapper.selectList(membershipWrapper);
        if (memberships.isEmpty()) return false;

        List<Long> groupIds = memberships.stream().map(GroupMember::getGroupId).toList();
        LambdaQueryWrapper<ChatMessage> groupWrapper = new LambdaQueryWrapper<>();
        groupWrapper.eq(ChatMessage::getFilePath, storedName)
                .in(ChatMessage::getGroupId, groupIds);
        return chatMessageMapper.selectCount(groupWrapper) > 0;
    }

    @Override
    public String getFileUrl(String fileName) {
        String normalized = normalizeStoredName(fileName);
        if (normalized == null) throw new IllegalArgumentException("文件名无效");
        return "/api/v1/file/content/" + normalized;
    }

    @Override
    public boolean isAllowedType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return false;
        }
        String suffix = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        Set<String> allowed = new HashSet<>(Arrays.asList(allowedTypes.split(",")));
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
    public String getFileNameFromToken(String signToken) {
        if (signToken == null || !signToken.matches("(?i)^[0-9a-f]{32}$")) return null;
        String redisKey = "preview:" + signToken;
        String value = redisTemplate.opsForValue().get(redisKey);
        if (value == null) return null;
        int separator = value.lastIndexOf(':');
        return separator > 0 ? value.substring(0, separator) : null;
    }

    /**
     * 生成图片缩略图（300x300）
     * PRD: 图片自动生成缩略图（300x300），用于卡片展示
     */
    @Override
    public String generateThumbnail(String fileName) {
        try {
            File sourceFile = resolveStoredFile(fileName);
            if (!sourceFile.exists()) return null;

            BufferedImage sourceImage = ImageIO.read(sourceFile);
            if (sourceImage == null) return null;

            // 等比缩放到 300x300 以内
            int sourceWidth = sourceImage.getWidth();
            int sourceHeight = sourceImage.getHeight();
            int targetWidth, targetHeight;

            if (sourceWidth > sourceHeight) {
                targetWidth = THUMBNAIL_SIZE;
                targetHeight = (int) ((double) sourceHeight / sourceWidth * THUMBNAIL_SIZE);
            } else {
                targetHeight = THUMBNAIL_SIZE;
                targetWidth = (int) ((double) sourceWidth / sourceHeight * THUMBNAIL_SIZE);
            }

            BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = thumbnail.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(sourceImage, 0, 0, targetWidth, targetHeight, null);
            g2d.dispose();

            // 保存缩略图
            String suffix = fileName.substring(fileName.lastIndexOf(".") + 1);
            String thumbName = "thumb_" + fileName;
            ImageIO.write(thumbnail, suffix, resolveStoredFile(thumbName));

            return getFileUrl(thumbName);
        } catch (IOException e) {
            log.warn("生成缩略图失败: {}", e.getMessage());
            return null;
        }
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
            File thumbFile = resolveStoredFile(thumbName);
            if (thumbFile.exists()) {
                vo.setThumbnailUrl(getFileUrl(thumbName));
            } else {
                vo.setThumbnailUrl(generateThumbnail(metadata.getFilePath()));
            }
        }

        return vo;
    }

    private String calculateSha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("读取文件失败");
        }
    }

    private java.nio.file.Path getStorageRoot() {
        return java.nio.file.Paths.get(filePath).toAbsolutePath().normalize();
    }

    private File resolveStoredFile(String fileName) {
        String normalized = normalizeStoredName(fileName);
        if (normalized == null) throw new IllegalArgumentException("文件名无效");
        java.nio.file.Path root = getStorageRoot();
        java.nio.file.Path resolved = root.resolve(normalized).normalize();
        if (!resolved.getParent().equals(root)) {
            throw new IllegalArgumentException("文件名无效");
        }
        return resolved.toFile();
    }

    private String normalizeStoredName(String fileName) {
        if (fileName == null) return null;
        String value = fileName.trim();
        if (!value.matches("^(?:thumb_)?[0-9a-fA-F]{32}\\.[a-zA-Z0-9]{1,10}$")) return null;
        return value;
    }
}
