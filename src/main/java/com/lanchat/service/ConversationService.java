package com.lanchat.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface ConversationService {

    String ensurePrivateConversation(Long firstUserId, Long secondUserId);

    String ensureGroupConversation(Long groupId);

    /** 创建临时会话壳；成员由临时房间事务显式加入。 */
    String ensureTemporaryConversation(Long roomId);

    void addConversationMember(String conversationId, Long userId, String role);

    void removeConversationMember(String conversationId, Long userId);

    void removeAllConversationMembers(String conversationId);

    void updateStatus(String conversationId, String status);

    List<Long> getActiveMemberIds(String conversationId);

    List<String> getAccessibleConversationIds(Long userId);

    /** 根据消息目标解析并创建统一会话，同时拒绝伪造的 conversationId。 */
    String resolveForMessage(Long senderId,
                             Long toUserId,
                             Long groupId,
                             String requestedConversationId);

    boolean canAccess(String conversationId, Long userId);

    /** 校验当前成员状态是否允许发送新内容。 */
    boolean canSend(String conversationId, Long userId);

    /** 校验会话是否允许当前成员上传附件。 */
    boolean canUploadFile(String conversationId, Long userId);

    /** 校验会话是否允许当前成员下载附件。 */
    boolean canDownloadFile(String conversationId, Long userId);

    long nextSequence(String conversationId);

    long getLastSequence(String conversationId);

    void updateLastMessage(String conversationId, String messageId, Long senderId);

    void markRead(String conversationId, Long userId, long sequence);

    Map<String, Long> getReadPositions(Long userId);

    void markGroupMemberLeft(Long groupId, Long userId);

    void archiveGroupConversation(Long groupId);
}
