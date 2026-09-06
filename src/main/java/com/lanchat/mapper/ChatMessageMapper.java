package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * Locks the sender/client-id key before allocating a conversation sequence.
     * With the matching unique index this prevents concurrent technical-card
     * retries from consuming a sequence that will never have a message row.
     */
    @Select("""
            SELECT *
            FROM chat_message
            WHERE from_user_id = #{senderId}
              AND client_msg_id = #{clientMsgId}
            LIMIT 1
            FOR UPDATE
            """)
    ChatMessage selectBySenderAndClientMsgIdForUpdate(@Param("senderId") Long senderId,
                                                       @Param("clientMsgId") String clientMsgId);

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

    /**
     * Finds mention targets whose newly committed group read cursor has not yet
     * been captured as an immutable receipt.  The receipt is intentionally
     * per-message: a later leave/rejoin must not rewrite an earlier read fact.
     * The active membership period has an explicit sequence floor, so a
     * rejoin cursor cannot backfill receipts for messages that predate it.
     */
    @Select("""
            SELECT message_id, from_user_id
            FROM chat_message message
            JOIN conversation_member member
              ON member.conversation_id = message.conversation_id
             AND member.user_id = #{readerId}
             AND member.left_time IS NULL
            WHERE message.conversation_id = #{conversationId}
              AND message.group_id IS NOT NULL
              AND message.sequence <= #{sequence}
              AND message.sequence >= COALESCE(member.receipt_start_sequence, 1)
              AND message.from_user_id <> #{readerId}
              AND message.mention_user_ids IS NOT NULL
              AND FIND_IN_SET(#{readerId}, message.mention_user_ids) > 0
              AND NOT EXISTS (
                  SELECT 1
                  FROM mention_read_receipt receipt
                  WHERE receipt.message_id = message.message_id
                    AND receipt.user_id = #{readerId}
              )
            ORDER BY message.sequence ASC
            """)
    java.util.List<ChatMessage> selectUnrecordedMentionedMessagesReadBy(
            @Param("conversationId") String conversationId,
            @Param("readerId") Long readerId,
            @Param("sequence") Long sequence);

    /** Records newly observed group-mention receipts exactly once. */
    @Insert("""
            INSERT IGNORE INTO mention_read_receipt (message_id, user_id, read_at)
            SELECT message.message_id, #{readerId}, NOW()
            FROM chat_message message
            JOIN conversation_member member
              ON member.conversation_id = message.conversation_id
             AND member.user_id = #{readerId}
             AND member.left_time IS NULL
            WHERE message.conversation_id = #{conversationId}
              AND message.group_id IS NOT NULL
              AND message.sequence <= #{sequence}
              AND message.sequence >= COALESCE(member.receipt_start_sequence, 1)
              AND message.from_user_id <> #{readerId}
              AND message.mention_user_ids IS NOT NULL
              AND FIND_IN_SET(#{readerId}, message.mention_user_ids) > 0
              AND NOT EXISTS (
                  SELECT 1
                  FROM mention_read_receipt receipt
                  WHERE receipt.message_id = message.message_id
                    AND receipt.user_id = #{readerId}
              )
            """)
    int recordMentionReceiptsRead(@Param("conversationId") String conversationId,
                                  @Param("readerId") Long readerId,
                                  @Param("sequence") Long sequence);

    @Select("""
            SELECT user_id
            FROM mention_read_receipt
            WHERE message_id = #{messageId}
            """)
    java.util.List<Long> selectMentionReceiptReaderIds(@Param("messageId") String messageId);

    /** Clears receipt rows before their group messages are physically removed. */
    @Delete("""
            DELETE receipt
            FROM mention_read_receipt receipt
            INNER JOIN chat_message message ON message.message_id = receipt.message_id
            WHERE message.group_id = #{groupId}
            """)
    int deleteMentionReceiptsByGroupId(@Param("groupId") Long groupId);
}
