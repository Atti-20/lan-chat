package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.ConversationMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationMemberMapper extends BaseMapper<ConversationMember> {

    @Insert("""
            INSERT INTO conversation_member
                (conversation_id, user_id, role, last_read_sequence, unread_count,
                 is_muted, is_pinned, join_time)
            VALUES
                (#{conversationId}, #{userId}, #{role},
                 COALESCE((SELECT last_sequence
                           FROM conversation
                           WHERE id = #{conversationId}), 0),
                 0, 0, 0, NOW())
            ON DUPLICATE KEY UPDATE
                role = VALUES(role),
                last_read_sequence = IF(
                    left_time IS NULL,
                    last_read_sequence,
                    VALUES(last_read_sequence)
                ),
                unread_count = IF(left_time IS NULL, unread_count, 0),
                join_time = IF(left_time IS NULL, join_time, NOW()),
                left_time = NULL
            """)
    int insertIfAbsent(@Param("conversationId") String conversationId,
                       @Param("userId") Long userId,
                       @Param("role") String role);

    @Update("""
            UPDATE conversation_member
            SET unread_count = unread_count + 1
            WHERE conversation_id = #{conversationId}
              AND user_id <> #{senderId}
              AND left_time IS NULL
            """)
    int incrementUnread(@Param("conversationId") String conversationId,
                        @Param("senderId") Long senderId);

    @Update("""
            UPDATE conversation_member
            SET last_read_sequence = GREATEST(last_read_sequence, #{sequence})
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND left_time IS NULL
            """)
    int advanceReadSequence(@Param("conversationId") String conversationId,
                            @Param("userId") Long userId,
                            @Param("sequence") Long sequence);

    @Update("""
            UPDATE conversation_member member
            SET unread_count = (
                SELECT COUNT(*)
                FROM chat_message message
                WHERE message.conversation_id = member.conversation_id
                  AND message.sequence > member.last_read_sequence
                  AND message.from_user_id <> member.user_id
            )
            WHERE member.conversation_id = #{conversationId}
              AND member.user_id = #{userId}
              AND member.left_time IS NULL
            """)
    int recalculateUnread(@Param("conversationId") String conversationId,
                          @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM conversation_member
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND left_time IS NULL
            LIMIT 1
            """)
    ConversationMember selectActiveMember(@Param("conversationId") String conversationId,
                                          @Param("userId") Long userId);

    @Update("""
            UPDATE conversation_member
            SET left_time = NOW(), unread_count = 0
            WHERE conversation_id = #{conversationId} AND user_id = #{userId}
            """)
    int markLeft(@Param("conversationId") String conversationId,
                 @Param("userId") Long userId);

    @Update("""
            UPDATE conversation_member
            SET left_time = NOW(), unread_count = 0
            WHERE conversation_id = #{conversationId}
            """)
    int markAllLeft(@Param("conversationId") String conversationId);

    @Update("""
            UPDATE conversation_member
            SET left_time = COALESCE(left_time, NOW()), unread_count = 0
            WHERE user_id = #{userId}
            """)
    int markUserLeft(@Param("userId") Long userId);

    @Delete("DELETE FROM conversation_member WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
