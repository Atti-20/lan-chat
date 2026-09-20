package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.FileAccessLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileAccessLogMapper extends BaseMapper<FileAccessLog> {
}
