package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.FileMetadata;
import com.lanchat.entity.MessageRecall;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.FileMetadataMapper;
import com.lanchat.mapper.MessageRecallMapper;
import com.lanchat.service.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageServiceImpl.class);

    @Autowired
    private MessageRecallMapper messageRecallMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Value("${file.path}")
    private String filePath;

    /** 撤回时限：2分钟 */
    private static final long RECALL_LIMIT_SECONDS = 120;

    @Override
    public List<ChatMessage> getGroupHistory(Long groupId, int limit) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getGroupId, groupId)
                .orderByDesc(ChatMessage::getCreateTime)
                .last("LIMIT " + limit);
        List<ChatMessage> messages = list(wrapper);
        Collections.reverse(messages);
        return messages;
    }

    @Override
    public List<ChatMessage> getPrivateHistory(Long user1Id, Long user2Id, int limit) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w
                        .and(w1 -> w1.eq(ChatMessage::getFromUserId, user1Id).eq(ChatMessage::getToUserId, user2Id))
                        .or(w2 -> w2.eq(ChatMessage::getFromUserId, user2Id).eq(ChatMessage::getToUserId, user1Id)))
                .orderByDesc(ChatMessage::getCreateTime)
                .last("LIMIT " + limit);
        List<ChatMessage> messages = list(wrapper);
        Collections.reverse(messages);
        return messages;
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
            throw new RuntimeException("消息不存在");
        }

        // 只有发送者本人才能撤回（群聊中管理员不可撤回他人消息）
        if (!message.getFromUserId().equals(operatorId)) {
            throw new RuntimeException("只能撤回自己的消息");
        }

        // 检查是否在2分钟内
        if (message.getCreateTime() != null) {
            long secondsElapsed = Duration.between(message.getCreateTime(), LocalDateTime.now()).getSeconds();
            if (secondsElapsed > RECALL_LIMIT_SECONDS) {
                throw new RuntimeException("消息发送超过2分钟，无法撤回");
            }
        }

        // 阅后即焚消息已被阅读则无法撤回
        if (message.getIsBurn() == 1 && message.getStatus() == 2) {
            throw new RuntimeException("阅后即焚消息已被阅读，无法撤回");
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
        if (message.getContent() != null && message.getContent().startsWith("/file/")) {
            cleanupFileIfNoReferences(message.getContent());
        }

        return true;
    }

    @Override
    public void markAsBurned(String messageId) {
        LambdaUpdateWrapper<ChatMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatMessage::getMessageId, messageId)
                .set(ChatMessage::getStatus, 2)
                .set(ChatMessage::getContent, "");
        update(wrapper);
    }

    @Override
    public ChatMessage getByMessageId(String messageId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getMessageId, messageId);
        return getOne(wrapper);
    }

    @Override
    public List<ChatMessage> searchMessages(Long userId, String keyword, int limit) {
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

    /**
     * 检查文件是否被其他消息引用，若无引用则删除文件存储
     * PRD: 被撤回的文件/图片，服务器删除对应文件存储（若有其他消息引用该文件则不删除）
     */
    private void cleanupFileIfNoReferences(String fileUrl) {
        try {
            // 提取文件名
            String fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);

            // 检查是否有其他未撤回的消息引用该文件
            LambdaQueryWrapper<ChatMessage> refWrapper = new LambdaQueryWrapper<>();
            refWrapper.like(ChatMessage::getContent, fileUrl)
                    .eq(ChatMessage::getIsRecalled, 0);
            long refCount = count(refWrapper);

            if (refCount == 0) {
                // 无其他引用，查找文件元数据并删除
                LambdaQueryWrapper<FileMetadata> fileWrapper = new LambdaQueryWrapper<>();
                fileWrapper.eq(FileMetadata::getFilePath, fileName);
                FileMetadata fileMeta = fileMetadataMapper.selectOne(fileWrapper);

                if (fileMeta != null) {
                    // 删除物理文件
                    File physicalFile = new File(filePath + fileName);
                    if (physicalFile.exists()) {
                        physicalFile.delete();
                        log.info("撤回消息清理文件: {}", fileName);
                    }
                    // 删除文件元数据记录
                    fileMetadataMapper.deleteById(fileMeta.getId());
                }
            }
        } catch (Exception e) {
            log.warn("清理撤回文件时异常: {}", e.getMessage());
        }
    }
}
