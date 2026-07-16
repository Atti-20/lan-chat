package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.FileUploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileUploadSessionMapper extends BaseMapper<FileUploadSession> {

    @Select("SELECT * FROM file_upload_session WHERE upload_id = #{uploadId} FOR UPDATE")
    FileUploadSession selectByUploadIdForUpdate(@Param("uploadId") String uploadId);
}
