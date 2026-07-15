package com.lanchat.service;

import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.entity.FileMetadata;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    /** 检查当前用户可访问的文件是否已存在（秒传检测） */
    FileUploadVO checkFile(FileCheckDTO dto, Long userId);

    /** 上传文件 */
    FileUploadVO uploadFile(MultipartFile file, Long userId);

    /** 根据哈希获取文件元数据 */
    FileMetadata getByHash(String fileHash);

    /** 根据服务端存储名获取元数据（缩略图会映射回原文件） */
    FileMetadata getByStoredName(String fileName);

    /** 判断用户是否为上传者或消息会话参与者 */
    boolean canAccessFile(String fileName, Long userId);

    /** 根据文件名获取文件访问路径 */
    String getFileUrl(String fileName);

    /** 校验文件类型是否允许 */
    boolean isAllowedType(String fileName);

    /** 生成临时签名文件URL（有效期10分钟，生成前校验用户权限） */
    String generatePreviewUrl(String fileName, Long userId);

    /** 生成图片缩略图 */
    String generateThumbnail(String fileName);

    /** 校验签名并获取文件名，无效或过期时返回 null */
    String getFileNameFromToken(String signToken);
}
