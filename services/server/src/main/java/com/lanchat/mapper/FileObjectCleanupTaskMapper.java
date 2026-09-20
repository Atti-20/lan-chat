package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.FileObjectCleanupTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileObjectCleanupTaskMapper extends BaseMapper<FileObjectCleanupTask> {

    @Select("SELECT * FROM file_object_cleanup_task WHERE id = #{taskId} FOR UPDATE")
    FileObjectCleanupTask selectByIdForUpdate(@Param("taskId") Long taskId);

    @Select("""
            SELECT * FROM file_object_cleanup_task
            WHERE storage_type = #{storageType} AND object_key = #{objectKey}
            FOR UPDATE
            """)
    FileObjectCleanupTask selectByObjectForUpdate(@Param("storageType") String storageType,
                                                  @Param("objectKey") String objectKey);

    @Insert("""
            INSERT INTO file_object_cleanup_task
                (storage_type, object_key, reason, task_type, upload_id,
                 part_number, attempts, next_retry_at, last_error,
                 create_time, update_time)
            VALUES
                (#{storageType}, #{objectKey}, #{reason}, #{taskType}, #{uploadId},
                 #{partNumber}, 0, #{nextRetryAt}, NULL, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                id = LAST_INSERT_ID(id),
                reason = VALUES(reason),
                next_retry_at = CASE
                    WHEN task_type = 'DELETE' OR VALUES(task_type) = 'DELETE'
                        THEN LEAST(next_retry_at, NOW())
                    ELSE LEAST(next_retry_at, VALUES(next_retry_at))
                END,
                upload_id = CASE
                    WHEN task_type = 'DELETE' OR VALUES(task_type) = 'DELETE'
                        THEN NULL ELSE VALUES(upload_id)
                END,
                part_number = CASE
                    WHEN task_type = 'DELETE' OR VALUES(task_type) = 'DELETE'
                        THEN NULL ELSE VALUES(part_number)
                END,
                task_type = CASE
                    WHEN task_type = 'DELETE' OR VALUES(task_type) = 'DELETE'
                        THEN 'DELETE' ELSE VALUES(task_type)
                END,
                update_time = NOW()
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int enqueue(FileObjectCleanupTask task);
}
