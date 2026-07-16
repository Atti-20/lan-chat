package com.lanchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lanchat.dto.ReliableMessageResult;
import com.lanchat.entity.ChatMessage;

import java.util.List;

public interface ChatMessageService extends IService<ChatMessage> {

    /** 获取群聊历史消息 */
    List<ChatMessage> getGroupHistory(Long groupId, int limit);

    /** 获取私聊历史消息 */
    List<ChatMessage> getPrivateHistory(Long user1Id, Long user2Id, int limit);

    /** 按统一会话和序列号游标获取历史消息。 */
    List<ChatMessage> getConversationHistory(String conversationId,
                                             Long userId,
                                             Long beforeSequence,
                                             int limit);

    /** 重连后补拉指定会话中缺失的消息。 */
    List<ChatMessage> getMessagesAfter(String conversationId,
                                       Long userId,
                                       long afterSequence,
                                       int limit);

    /** 持久化可靠消息；只有事务提交成功后调用方才能返回 ACK。 */
    ReliableMessageResult saveReliableMessage(ChatMessage message,
                                              String requestedConversationId);

    ChatMessage getByClientMsgId(Long senderId, String clientMsgId);

    /** 标记消息为已读 */
    void markAsRead(Long fromUserId, Long toUserId);

    /** 撤回消息 */
    boolean recallMessage(String messageId, Long operatorId);

    /** 标记消息为已焚毁（操作者必须能够访问该消息） */
    void markAsBurned(String messageId, Long operatorId);

    /** 判断用户是否仍可访问消息所属统一会话。 */
    boolean canAccessMessage(String messageId, Long userId);

    /** 根据消息ID获取消息 */
    ChatMessage getByMessageId(String messageId);

    /** 搜索消息 */
    List<ChatMessage> searchMessages(Long userId, String keyword, int limit);
}
