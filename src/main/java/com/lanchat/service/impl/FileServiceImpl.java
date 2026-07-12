package com.lanchat.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.entity.FileMetadata;
import com.lanchat.mapper.FileMetadataMapper;
import com.lanchat.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
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

    /** 图片文件后缀集合 */
    private static final Set<String> IMAGE_SUFFIXES = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

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
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (!isAllowedType(originalFilename)) {
            throw new IllegalArgumentException("不支持的文件类型");
        }

        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("文件大小超过限制（100MB）");
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage());
        }
        String fileHash = DigestUtil.sha256Hex(fileBytes);

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
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String newFileName = UUID.randomUUID().toString().replace("-", "") + suffix;

        // 确保目录存在
        File destDir = new File(filePath);
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        // 保存文件
        File destFile = new File(filePath + newFileName);
        try {
            file.transferTo(destFile);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage());
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
        LambdaQueryWrapper<FileMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileMetadata::getFileHash, fileHash);
        return fileMetadataMapper.selectOne(wrapper);
    }

    @Override
    public String getFileUrl(String fileName) {
        return "/file/" + fileName;
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
     * 生成临时签名预览URL
     * PRD: 预览链接为临时签名URL，有效期10分钟，与用户Token绑定
     */
    @Override
    public String generatePreviewUrl(String fileName, Long userId) {
        // 生成签名 token 并存入 Redis，10分钟后自动过期
        String signToken = UUID.randomUUID().toString().replace("-", "");
        String redisKey = "preview:" + signToken;
        redisTemplate.opsForValue().set(redisKey, fileName + ":" + userId,
                PREVIEW_URL_EXPIRE_MINUTES, TimeUnit.MINUTES);
        return "/api/v1/file/preview/" + signToken;
    }

    /**
     * 验证预览签名是否有效
     */
    @Override
    public boolean validatePreviewToken(String signToken, Long userId) {
        String redisKey = "preview:" + signToken;
        String value = redisTemplate.opsForValue().get(redisKey);
        if (value == null) return false;
        String[] parts = value.split(":");
        return parts.length == 2 && parts[1].equals(userId.toString());
    }

    /**
     * 根据签名获取文件名
     */
    @Override
    public String getFileNameFromToken(String signToken) {
        String redisKey = "preview:" + signToken;
        String value = redisTemplate.opsForValue().get(redisKey);
        if (value == null) return null;
        return value.split(":")[0];
    }

    /**
     * 生成图片缩略图（300x300）
     * PRD: 图片自动生成缩略图（300x300），用于卡片展示
     */
    @Override
    public String generateThumbnail(String fileName) {
        try {
            File sourceFile = new File(filePath + fileName);
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
            ImageIO.write(thumbnail, suffix, new File(filePath + thumbName));

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
            File thumbFile = new File(filePath + thumbName);
            if (thumbFile.exists()) {
                vo.setThumbnailUrl(getFileUrl(thumbName));
            } else {
                vo.setThumbnailUrl(generateThumbnail(metadata.getFilePath()));
            }
        }

        return vo;
    }
}
