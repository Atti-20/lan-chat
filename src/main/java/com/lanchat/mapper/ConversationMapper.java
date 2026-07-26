package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.dto.ConversationSummary;
import com.lanchat.entity.Conversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Insert("""
            INSERT IGNORE INTO conversation
                (id, type, source_id, last_sequence, status, create_time, update_time)
            VALUES
                (#{id}, #{type}, #{sourceId}, 0, 'ACTIVE', NOW(), NOW())
            """)
    int insertIfAbsent(@Param("id") String id,
                       @Param("type") String type,
                       @Param("sourceId") Long sourceId);

    /** 当前事务持有会话行锁，直到消息和会话摘要一起提交。 */
    @Update("""
            UPDATE conversation
            SET last_sequence = last_sequence + 1,
                update_time = NOW()
            WHERE id = #{conversationId} AND status = 'ACTIVE'
            """)
    int incrementSequence(@Param("conversationId") String conversationId);

    @Select("SELECT last_sequence FROM conversation WHERE id = #{conversationId}")
    Long selectLastSequence(@Param("conversationId") String conversationId);

    @Select("SELECT last_sequence FROM conversation WHERE id = #{conversationId} FOR UPDATE")
    Long selectLastSequenceForUpdate(@Param("conversationId") String conversationId);

    @Select("""
            SELECT
                c.id AS conversation_id,
                LOWER(c.type) AS kind,
                CASE
                    WHEN c.type = 'PRIVATE' THEN
                        CASE
                            WHEN CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(c.id, ':', 2), ':', -1)
                                      AS UNSIGNED) = #{userId}
                            THEN CAST(SUBSTRING_INDEX(c.id, ':', -1) AS UNSIGNED)
                            ELSE CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(c.id, ':', 2), ':', -1)
                                      AS UNSIGNED)
                        END
                    ELSE c.source_id
                END AS target_id,
                c.last_sequence,
                cm.last_read_sequence,
                cm.unread_count,
                last_message.content AS last_message,
                last_message.type AS last_message_type,
                last_message.create_time AS last_message_at,
                CASE
                    WHEN c.type = 'PRIVATE'
                    THEN COALESCE(friendship.is_pinned, cm.is_pinned, 0)
                    ELSE COALESCE(cm.is_pinned, 0)
                END AS pinned,
                CASE
                    WHEN c.type = 'PRIVATE'
                    THEN COALESCE(friendship.is_muted, cm.is_muted, 0)
                    ELSE COALESCE(cm.is_muted, 0)
                END AS muted
            FROM conversation_member cm
            JOIN conversation c ON c.id = cm.conversation_id
            LEFT JOIN chat_message last_message ON last_message.message_id = c.last_message_id
            LEFT JOIN friendship ON friendship.user_id = #{userId}
                AND friendship.friend_id = CASE
                    WHEN c.type = 'PRIVATE' THEN
                        CASE
                            WHEN CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(c.id, ':', 2), ':', -1)
                                      AS UNSIGNED) = #{userId}
                            THEN CAST(SUBSTRING_INDEX(c.id, ':', -1) AS UNSIGNED)
                            ELSE CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(c.id, ':', 2), ':', -1)
                                      AS UNSIGNED)
                        END
                    ELSE NULL
                END
            WHERE cm.user_id = #{userId}
              AND cm.left_time IS NULL
              AND c.status <> 'DESTROYED'
              AND (
                  c.type <> 'GROUP'
                  OR EXISTS (
                      SELECT 1
                      FROM group_member active_group_member
                      WHERE active_group_member.group_id = c.source_id
                        AND active_group_member.user_id = #{userId}
                  )
              )
            ORDER BY pinned DESC, last_message_at DESC, c.update_time DESC, c.id ASC
            """)
    List<ConversationSummary> selectSummaries(@Param("userId") Long userId);

    @Update("""
            UPDATE conversation
            SET last_message_id = #{messageId}, update_time = NOW()
            WHERE id = #{conversationId}
            """)
    int updateLastMessage(@Param("conversationId") String conversationId,
                          @Param("messageId") String messageId);

    @Update("""
            UPDATE conversation
            SET status = #{status}, update_time = NOW()
            WHERE id = #{conversationId}
            """)
    int updateStatus(@Param("conversationId") String conversationId,
                     @Param("status") String status);
}
