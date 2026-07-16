package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.TemporaryRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TemporaryRoomMapper extends BaseMapper<TemporaryRoom> {

    @Select("SELECT * FROM temporary_room WHERE id = #{roomId} FOR UPDATE")
    TemporaryRoom selectByIdForUpdate(@Param("roomId") Long roomId);

    @Select("SELECT * FROM temporary_room WHERE room_code = #{roomCode} FOR UPDATE")
    TemporaryRoom selectByRoomCodeForUpdate(@Param("roomCode") String roomCode);

    @Select("SELECT COUNT(*) FROM temporary_room WHERE room_code = #{roomCode}")
    long countByRoomCode(@Param("roomCode") String roomCode);

    @Select("""
            SELECT tr.*
            FROM temporary_room tr
            JOIN conversation_member cm
              ON cm.conversation_id = CONCAT('temporary:', tr.id)
            WHERE cm.user_id = #{userId}
              AND cm.left_time IS NULL
              AND tr.status <> 'DESTROYED'
            ORDER BY tr.update_time DESC, tr.id DESC
            """)
    List<TemporaryRoom> selectByMemberId(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM conversation_member
            WHERE conversation_id = CONCAT('temporary:', #{roomId})
              AND left_time IS NULL
            """)
    long countActiveMembers(@Param("roomId") Long roomId);

    @Select("""
            SELECT role
            FROM conversation_member
            WHERE conversation_id = CONCAT('temporary:', #{roomId})
              AND user_id = #{userId}
              AND left_time IS NULL
            LIMIT 1
            """)
    String selectMemberRole(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM temporary_room
            WHERE status = 'ACTIVE'
              AND expires_at <= #{now}
            ORDER BY expires_at, id
            """)
    List<TemporaryRoom> selectExpiredActiveRooms(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE temporary_room
            SET status = #{nextStatus}, update_time = #{now}
            WHERE id = #{roomId}
              AND status = 'ACTIVE'
              AND expires_at <= #{now}
            """)
    int transitionExpiredRoom(@Param("roomId") Long roomId,
                              @Param("nextStatus") String nextStatus,
                              @Param("now") LocalDateTime now);

    @Update("UPDATE temporary_room SET update_time = #{now} WHERE id = #{roomId}")
    int touch(@Param("roomId") Long roomId, @Param("now") LocalDateTime now);
}
