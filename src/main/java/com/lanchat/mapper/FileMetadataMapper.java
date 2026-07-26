package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.FileMetadata;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileMetadataMapper extends BaseMapper<FileMetadata> {

    /**
     * Serializes deduplicated grant creation against lifecycle deletion for the
     * same global SHA-256 object.
     */
    @Select("""
            SELECT id, file_hash, file_name, file_path, file_size, file_type,
                   file_suffix, storage_type, upload_user_id, create_time
            FROM file_metadata
            WHERE file_hash = #{fileHash}
            FOR UPDATE
            """)
    FileMetadata selectByHashForUpdate(@Param("fileHash") String fileHash);

    /**
     * Deletes only an unreferenced upload. Every durable consumer, including another
     * user's deduplicated-upload grant and user/group avatars, keeps the shared object
     * alive. The archived user's own grant/avatar is intentionally excluded because
     * that relationship is removed in the same lifecycle transaction.
     */
    @Delete("""
            DELETE FROM file_metadata
            WHERE id = #{fileId}
              AND upload_user_id = #{userId}
              AND NOT EXISTS (
                  SELECT 1 FROM chat_message
                  WHERE file_path = #{filePath} AND is_recalled = 0
              )
              AND NOT EXISTS (
                  SELECT 1 FROM broadcast_evidence WHERE file_id = #{fileId}
              )
              AND NOT EXISTS (
                  SELECT 1 FROM file_transfer WHERE file_metadata_id = #{fileId}
              )
              AND NOT EXISTS (
                  SELECT 1 FROM file_upload_session WHERE completed_file_id = #{fileId}
              )
              AND NOT EXISTS (
                  SELECT 1 FROM file_access_grant
                  WHERE file_id = #{fileId}
                    AND user_id <> #{userId}
              )
              AND NOT EXISTS (
                  SELECT 1 FROM `user`
                  WHERE id <> #{userId}
                    AND avatar IN (
                        CONCAT('/api/v1/file/content/', #{filePath}),
                        CONCAT('/api/v1/file/content/thumb_', #{filePath})
                    )
              )
              AND NOT EXISTS (
                  SELECT 1 FROM chat_group
                  WHERE avatar IN (
                      CONCAT('/api/v1/file/content/', #{filePath}),
                      CONCAT('/api/v1/file/content/thumb_', #{filePath})
                  )
              )
            """)
    int deleteUnreferencedUpload(@Param("fileId") Long fileId,
                                 @Param("userId") Long userId,
                                 @Param("filePath") String filePath);
}
