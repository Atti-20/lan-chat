package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.FileUploadPart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileUploadPartMapper extends BaseMapper<FileUploadPart> {

    @Delete("DELETE FROM file_upload_part WHERE upload_id = #{uploadId}")
    int deleteByUploadId(@Param("uploadId") String uploadId);
}
