package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.Conversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
