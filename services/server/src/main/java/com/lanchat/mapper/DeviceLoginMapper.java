package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.DeviceLogin;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DeviceLoginMapper extends BaseMapper<DeviceLogin> {

    /**
     * Compatibility name retained for callers. The owning user row is locked first, then this
     * exact unique-key lookup performs a current read. A plain snapshot read is unsafe here:
     * a transaction that began before a competing login committed could otherwise observe the
     * already-revoked session and fail its strict deactivate count.
     */
    @Select("""
            SELECT *
            FROM device_login
            WHERE user_id = #{userId}
              AND active_device_type = #{deviceType}
            ORDER BY id
            FOR UPDATE
            """)
    List<DeviceLogin> selectActiveByTypeForUpdate(@Param("userId") Long userId,
                                                   @Param("deviceType") String deviceType);

    /** Reads the current active-session set after UserMapper.lockById serializes the lifecycle. */
    @Select("""
            SELECT *
            FROM device_login
            WHERE user_id = #{userId}
              AND active_device_type IS NOT NULL
            ORDER BY id
            FOR UPDATE
            """)
    List<DeviceLogin> selectActiveForUpdate(@Param("userId") Long userId);
}
