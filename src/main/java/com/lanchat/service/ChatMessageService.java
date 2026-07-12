package com.lanchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lanchat.entity.ChatMessage;

import java.util.List;

public interface ChatMessageService extends IService<ChatMessage> {

    /** 获取群聊历史消息 */
    List<ChatMessage> getGroupHistory(Long groupId, int limit);

    /** 获取私聊历史消息 */
    List<ChatMessage> getPrivateHistory(Long user1Id, Long user2Id, int limit);

    /** 标记消息为已读 */
    void markAsRead(Long fromUserId, Long toUserId);

    /** 撤回消息 */
    boolean recallMessage(String messageId, Long operatorId);

    /** 标记消息为已焚毁 */
    void markAsBurned(String messageId);

    /** 根据消息ID获取消息 */
    ChatMessage getByMessageId(String messageId);

    /** 搜索消息 */
    List<ChatMessage> searchMessages(Long userId, String keyword, int limit);
}
