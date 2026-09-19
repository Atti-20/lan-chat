package com.lanchat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.FileMetadata;
import com.lanchat.mapper.FileAccessGrantMapper;
import com.lanchat.mapper.FileMetadataMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/** Shared recall/group-removal cleanup, preserving independently referenced objects. */
@Service
public class MessageAttachmentCleanup {
    private final FileMetadataMapper metadata;
    private final FileAccessGrantMapper grants;
    private final FileService files;
    public MessageAttachmentCleanup(FileMetadataMapper metadata, FileAccessGrantMapper grants, FileService files) {
        this.metadata=metadata;this.grants=grants;this.files=files;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanup(Collection<ChatMessage> originals) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Attachment cleanup requires its message transaction");
        }
        originals.stream().filter(message -> message.getFilePath()!=null && !"text".equals(message.getType())
                        && message.getFilePath().matches("[0-9a-fA-F]{32}\\.[a-zA-Z0-9]{1,10}"))
                .sorted(Comparator.comparing(ChatMessage::getFilePath)).forEach(original -> {
                    String name=original.getFilePath();
                    FileMetadata file=metadata.selectOne(new LambdaQueryWrapper<FileMetadata>().eq(FileMetadata::getFilePath,name));
                    if (file==null || !Objects.equals(file.getUploadUserId(),original.getFromUserId())) return;
                    if (metadata.deleteUnreferencedMessageAttachment(file.getId(),original.getFromUserId(),name)!=1) return;
                    grants.deleteByFileId(file.getId());
                    // FileService persists its existing cleanup outbox in this same transaction.
                    // Failures must roll back metadata and message changes, not disappear in a log.
                    files.deleteStoredObjects(file);
                });
    }
}
