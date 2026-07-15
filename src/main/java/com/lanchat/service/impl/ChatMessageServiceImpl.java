package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lanchat.common.FileReferenceUtil;
import com.lanchat.dto.ReliableMessageResult;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.FileMetadata;
import com.lanchat.entity.MessageRecall;
import com.lanchat.entity.GroupMember;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.FileAccessGrantMapper;
import com.lanchat.mapper.FileMetadataMapper;
import com.lanchat.mapper.MessageRecallMapper;
import com.lanchat.mapper.GroupMemberMapper;
import com.lanchat.service.ChatMessageService;
import com.lanchat.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageServiceImpl.class);

    @Autowired
    private MessageRecallMapper messageRecallMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private FileAccessGrantMapper fileAccessGrantMapper;

    @Autowired
    private GroupMemberMapper groupMemberMapper;

    @Autowired
    private ConversationService conversationService;

    @Value("${file.path}")
    private String filePath;

    /** 撤回时限：2分钟 */
    private static final long RECALL_LIMIT_SECONDS = 120;

    @Override
    public List<ChatMessage> getGroupHistory(Long groupId, int limit) {
        String conversationId = conversationService.ensureGroupConversation(groupId);
        return queryHistory(conversationId, null, limit);
    }

    @Override
    public List<ChatMessage> getPrivateHistory(Long user1Id, Long user2Id, int limit) {
        String conversationId = conversationService.ensurePrivateConversation(user1Id, user2Id);
        return queryHistory(conversationId, null, limit);
    }

    @Override
    public List<ChatMessage> getConversationHistory(String conversationId,
                                                    Long userId,
                                                    Long beforeSequence,
                                                    int limit) {
        if (!conversationService.canAccess(conversationId, userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        return queryHistory(conversationId, beforeSequence, limit);
    }

    @Override
    public List<ChatMessage> getMessagesAfter(String conversationId,
                                              Long userId,
                                              long afterSequence,
                                              int limit) {
        if (!conversationService.canAccess(conversationId, userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return list(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .gt(ChatMessage::getSequence, Math.max(0, afterSequence))
                .orderByAsc(ChatMessage::getSequence)
                .last("LIMIT " + safeLimit));
    }

    @Override
    @Transactional
    public ReliableMessageResult saveReliableMessage(ChatMessage message,
                                                     String requestedConversationId) {
        if (message == null || message.getFromUserId() == null
                || message.getClientMsgId() == null || message.getClientMsgId().isBlank()) {
            throw new IllegalArgumentException("消息幂等参数不完整");
        }

        ChatMessage existing = getByClientMsgId(message.getFromUserId(), message.getClientMsgId());
        if (existing != null) {
            return new ReliableMessageResult(existing, true);
        }

        String conversationId = conversationService.resolveForMessage(
                message.getFromUserId(),
                message.getToUserId(),
                message.getGroupId(),
                requestedConversationId
        );
        message.setConversationId(conversationId);
        message.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        message.setSequence(conversationService.nextSequence(conversationId));
        message.setCreateTime(LocalDateTime.now());
        if (message.getStatus() == null) message.setStatus(0);
        if (message.getIsRecalled() == null) message.setIsRecalled(0);
        if (message.getIsBurn() == null) message.setIsBurn(0);
        if (message.getBurnDuration() == null) message.setBurnDuration(5);

        save(message);
        conversationService.updateLastMessage(conversationId, message.getMessageId(), message.getFromUserId());
        return new ReliableMessageResult(message, false);
    }

    @Override
    public ChatMessage getByClientMsgId(Long senderId, String clientMsgId) {
        if (senderId == null || clientMsgId == null || clientMsgId.isBlank()) return null;
        return getOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getFromUserId, senderId)
                .eq(ChatMessage::getClientMsgId, clientMsgId)
                .last("LIMIT 1"));
    }

    @Override
    public void markAsRead(Long fromUserId, Long toUserId) {
        LambdaUpdateWrapper<ChatMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatMessage::getFromUserId, fromUserId)
                .eq(ChatMessage::getToUserId, toUserId)
                .eq(ChatMessage::getStatus, 0)
                .set(ChatMessage::getStatus, 1);
        update(wrapper);
    }

    @Override
    @Transactional
    public boolean recallMessage(String messageId, Long operatorId) {

        ChatMessage message = getByMessageId(messageId);
        if (message == null) {
            throw new IllegalArgumentException("消息不存在");
        }

        if (Integer.valueOf(1).equals(message.getIsRecalled())) {
            log.info("消息 {} 已经被撤回过，忽略本次重复操作", messageId);
            return true;
        }

        // 只有发送者本人才能撤回（群聊中管理员不可撤回他人消息）
        if (!message.getFromUserId().equals(operatorId)) {
            throw new IllegalArgumentException("只能撤回自己的消息");
        }

        // 检查是否在2分钟内
        if (message.getCreateTime() != null) {
            long secondsElapsed = Duration.between(message.getCreateTime(), LocalDateTime.now()).getSeconds();
            if (secondsElapsed > RECALL_LIMIT_SECONDS) {
                throw new IllegalArgumentException("消息发送超过2分钟，无法撤回");
            }
        }

        // 阅后即焚消息已被阅读则无法撤回
        if (Integer.valueOf(1).equals(message.getIsBurn()) && Integer.valueOf(2).equals(message.getStatus())) {
            throw new IllegalArgumentException("阅后即焚消息已被阅读，无法撤回");
        }

        // 标记消息为已撤回，清空内容
        LambdaUpdateWrapper<ChatMessage> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(ChatMessage::getMessageId, messageId)
                .set(ChatMessage::getIsRecalled, 1)
                .set(ChatMessage::getContent, "");
        update(updateWrapper);

        // 记录撤回日志
        MessageRecall recall = new MessageRecall();
        recall.setMessageId(messageId);
        recall.setOperatorId(operatorId);
        recall.setRecallTime(LocalDateTime.now());
        messageRecallMapper.insert(recall);

        // 撤回文件/图片消息时，检查是否有其他消息引用该文件
        // 若无其他引用，删除文件存储（已下载到本地的文件不受影响）
        if (message.getContent() != null
                && (message.getContent().contains("/file/")
                || message.getContent().contains("/api/v1/file/content/"))) {
            cleanupFileIfNoReferences(message.getContent());
        }

        return true;
    }

    @Override
    public void markAsBurned(String messageId, Long operatorId) {
        ChatMessage message = getByMessageId(messageId);
        if (message == null) {
            throw new IllegalArgumentException("消息不存在");
        }
        if (!Integer.valueOf(1).equals(message.getIsBurn())) {
            throw new IllegalArgumentException("该消息不是阅后即焚消息");
        }
        if (!canAccessMessage(messageId, operatorId)) {
            throw new IllegalArgumentException("无权操作此消息");
        }
        if (Integer.valueOf(1).equals(message.getIsRecalled())) {
            throw new IllegalArgumentException("消息已撤回");
        }

        LambdaUpdateWrapper<ChatMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatMessage::getMessageId, messageId)
                .eq(ChatMessage::getIsBurn, 1)
                .eq(ChatMessage::getIsRecalled, 0)
                .set(ChatMessage::getStatus, 2)
                .set(ChatMessage::getContent, "");
        update(wrapper);
    }

    @Override
    public boolean canAccessMessage(String messageId, Long userId) {
        if (messageId == null || userId == null) return false;
        ChatMessage message = getByMessageId(messageId);
        if (message == null) return false;

        if (message.getGroupId() != null) {
            LambdaQueryWrapper<GroupMember> memberWrapper = new LambdaQueryWrapper<>();
            memberWrapper.eq(GroupMember::getGroupId, message.getGroupId())
                    .eq(GroupMember::getUserId, userId);
            return groupMemberMapper.selectCount(memberWrapper) > 0;
        }

        return userId.equals(message.getFromUserId()) || userId.equals(message.getToUserId());
    }

    @Override
    public ChatMessage getByMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) return null;
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getMessageId, messageId);
        return getOne(wrapper);
    }

    @Override
    public List<ChatMessage> searchMessages(Long userId, String keyword, int limit) {
        limit = Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w
                        .eq(ChatMessage::getFromUserId, userId)
                        .or()
                        .eq(ChatMessage::getToUserId, userId))
                .like(ChatMessage::getContent, keyword)
                .eq(ChatMessage::getIsRecalled, 0)
                .ne(ChatMessage::getStatus, 2)
                .orderByDesc(ChatMessage::getCreateTime)
                .last("LIMIT " + limit);
        return list(wrapper);
    }

    private List<ChatMessage> queryHistory(String conversationId, Long beforeSequence, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, conversationId);
        if (beforeSequence != null && beforeSequence > 0) {
            wrapper.lt(ChatMessage::getSequence, beforeSequence);
        }
        wrapper.orderByDesc(ChatMessage::getSequence).last("LIMIT " + safeLimit);
        List<ChatMessage> messages = list(wrapper);
        Collections.reverse(messages);
        return messages;
    }

    /**
     * 检查文件是否被其他消息引用，若无引用则删除文件存储
     * PRD: 被撤回的文件/图片，服务器删除对应文件存储（若有其他消息引用该文件则不删除）
     */
    private void cleanupFileIfNoReferences(String messageContent) {
        try {
            Set<String> fileNames = FileReferenceUtil.extractStoredNames(messageContent);

            Path root = Paths.get(filePath).toAbsolutePath().normalize();
            for (String fileName : fileNames) {
                LambdaQueryWrapper<ChatMessage> refWrapper = new LambdaQueryWrapper<>();
                refWrapper.eq(ChatMessage::getFilePath, fileName)
                        .eq(ChatMessage::getIsRecalled, 0);
                if (count(refWrapper) > 0) continue;

                LambdaQueryWrapper<FileMetadata> fileWrapper = new LambdaQueryWrapper<>();
                fileWrapper.eq(FileMetadata::getFilePath, fileName);
                FileMetadata fileMeta = fileMetadataMapper.selectOne(fileWrapper);
                if (fileMeta != null) {
                    Files.deleteIfExists(root.resolve(fileName).normalize());
                    Files.deleteIfExists(root.resolve("thumb_" + fileName).normalize());
                    fileAccessGrantMapper.deleteByFileId(fileMeta.getId());
                    fileMetadataMapper.deleteById(fileMeta.getId());
                    log.info("撤回消息清理文件: {}", fileName);
                }
            }
        } catch (Exception e) {
            log.warn("清理撤回文件时异常: {}", e.getMessage());
        }
    }
}
