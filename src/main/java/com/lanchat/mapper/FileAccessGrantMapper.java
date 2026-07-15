package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.FileAccessGrant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileAccessGrantMapper extends BaseMapper<FileAccessGrant> {

    @Insert("""
            INSERT INTO file_access_grant (file_id, user_id, grant_type, create_time)
            VALUES (#{fileId}, #{userId}, #{grantType}, NOW())
            ON DUPLICATE KEY UPDATE grant_type = VALUES(grant_type)
            """)
    int grant(@Param("fileId") Long fileId,
              @Param("userId") Long userId,
              @Param("grantType") String grantType);

    @Delete("DELETE FROM file_access_grant WHERE file_id = #{fileId}")
    int deleteByFileId(@Param("fileId") Long fileId);
}
