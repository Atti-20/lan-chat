package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /** 私聊可用单一 status 持久化已读；群聊仍以成员 last_read_sequence 为准。 */
    @Update("""
            UPDATE chat_message
            SET status = 1
            WHERE conversation_id = #{conversationId}
              AND from_user_id <> #{readerId}
              AND sequence <= #{sequence}
              AND status = 0
            """)
    int markPrivateMessagesRead(@Param("conversationId") String conversationId,
                                @Param("readerId") Long readerId,
                                @Param("sequence") Long sequence);
}
