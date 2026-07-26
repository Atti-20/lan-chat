package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * Serializes per-user quota checks such as COUNT(active uploads) -> INSERT.
     */
    @Select("SELECT id FROM `user` WHERE id = #{userId} FOR UPDATE")
    Long lockById(@Param("userId") Long userId);

    /**
     * Replaces account credentials and public profile data with an anonymous tombstone.
     */
    @Update("""
            UPDATE `user`
            SET username = #{anonymousUsername},
                password = #{disabledPassword},
                nickname = '已注销用户',
                avatar = '',
                signature = '',
                online = 0,
                last_login_at = NULL,
                status = 0,
                can_send_broadcast = 0,
                archived_at = #{archivedAt},
                archived_by = #{actorUserId},
                archive_reason = #{reason},
                mute_start = NULL,
                mute_end = NULL,
                update_time = #{archivedAt}
            WHERE id = #{userId}
              AND archived_at IS NULL
            """)
    int archiveAccount(@Param("userId") Long userId,
                       @Param("actorUserId") Long actorUserId,
                       @Param("anonymousUsername") String anonymousUsername,
                       @Param("disabledPassword") String disabledPassword,
                       @Param("reason") String reason,
                       @Param("archivedAt") LocalDateTime archivedAt);
}
