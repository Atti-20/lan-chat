package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanchat.entity.FileObjectCleanupTask;
import com.lanchat.entity.FileUploadPart;
import com.lanchat.mapper.FileObjectCleanupTaskMapper;
import com.lanchat.mapper.FileUploadPartMapper;
import com.lanchat.service.FileObjectCleanupService;
import com.lanchat.service.storage.FileObjectStorage;
import com.lanchat.service.storage.FileObjectStorageRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class FileObjectCleanupServiceImpl implements FileObjectCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FileObjectCleanupServiceImpl.class);
    private static final String DELETE = "DELETE";
    private static final String RECONCILE_UPLOAD_PART = "RECONCILE_UPLOAD_PART";

    private final FileObjectCleanupTaskMapper taskMapper;
    private final FileUploadPartMapper partMapper;
    private final FileObjectStorageRegistry storageRegistry;
    private final ObjectProvider<FileObjectCleanupService> selfProvider;

    public FileObjectCleanupServiceImpl(FileObjectCleanupTaskMapper taskMapper,
                                        FileUploadPartMapper partMapper,
                                        FileObjectStorageRegistry storageRegistry,
                                        ObjectProvider<FileObjectCleanupService> selfProvider) {
        this.taskMapper = taskMapper;
        this.partMapper = partMapper;
        this.storageRegistry = storageRegistry;
        this.selfProvider = selfProvider;
    }

    @Override
    @Transactional
    public void enqueue(String storageType, String objectKey, String reason) {
        persistAndSchedule(storageType, objectKey, reason, DELETE,
                null, null, LocalDateTime.now(), true);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueDetached(String storageType, String objectKey, String reason) {
        persistAndSchedule(storageType, objectKey, reason, DELETE,
                null, null, LocalDateTime.now(), true);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueuePartReconciliation(String storageType,
                                          String objectKey,
                                          String uploadId,
                                          int partNumber,
                                          String reason) {
        if (uploadId == null || !uploadId.matches("^[0-9a-fA-F]{32}$") || partNumber < 1) {
            throw new IllegalArgumentException("分片清理引用无效");
        }
        // The intent is persisted before object I/O. Delay reconciliation so
        // the following short part transaction has ample time to commit.
        persistAndSchedule(storageType, objectKey, reason, RECONCILE_UPLOAD_PART,
                uploadId, partNumber, LocalDateTime.now().plusMinutes(5), false);
    }

    @Override
    @Transactional
    public boolean claimPartReference(String storageType,
                                      String objectKey,
                                      String uploadId,
                                      int partNumber) {
        String normalizedType = storageType == null || storageType.isBlank()
                ? "LOCAL" : storageType.trim().toUpperCase(Locale.ROOT);
        if (objectKey == null || objectKey.isBlank()) return false;
        FileObjectCleanupTask task = taskMapper.selectByObjectForUpdate(
                normalizedType, objectKey.trim());
        if (task == null
                || !RECONCILE_UPLOAD_PART.equals(task.getTaskType())
                || !Objects.equals(uploadId, task.getUploadId())
                || !Integer.valueOf(partNumber).equals(task.getPartNumber())) {
            return false;
        }
        // Deleting this intent and writing the exact part reference occur in the
        // same caller transaction. Rollback restores the intent automatically.
        taskMapper.deleteById(task.getId());
        return true;
    }

    private void persistAndSchedule(String storageType,
                                    String objectKey,
                                    String reason,
                                    String taskType,
                                    String uploadId,
                                    Integer partNumber,
                                    LocalDateTime nextRetryAt,
                                    boolean attemptAfterCommit) {
        String normalizedType = storageType == null || storageType.isBlank()
                ? "LOCAL" : storageType.trim().toUpperCase(Locale.ROOT);
        if (objectKey == null || objectKey.isBlank() || objectKey.length() > 500) return;

        FileObjectCleanupTask task = new FileObjectCleanupTask();
        task.setStorageType(normalizedType);
        task.setObjectKey(objectKey.trim());
        task.setReason(safeText(reason, 80, "UNREFERENCED"));
        task.setTaskType(taskType);
        task.setUploadId(uploadId);
        task.setPartNumber(partNumber);
        task.setNextRetryAt(nextRetryAt);
        taskMapper.enqueue(task);
        if (!attemptAfterCommit) return;
        Long taskId = task.getId();
        if (taskId == null) {
            FileObjectCleanupTask persisted = taskMapper.selectOne(
                    new LambdaQueryWrapper<FileObjectCleanupTask>()
                            .eq(FileObjectCleanupTask::getStorageType, normalizedType)
                            .eq(FileObjectCleanupTask::getObjectKey, objectKey.trim())
                            .last("LIMIT 1"));
            taskId = persisted == null ? null : persisted.getId();
        }
        if (taskId == null) return;

        Long finalTaskId = taskId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    attemptThroughProxy(finalTaskId);
                }
            });
        } else {
            attemptThroughProxy(finalTaskId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean attemptTask(Long taskId) {
        if (taskId == null) return false;
        FileObjectCleanupTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) return true;

        try {
            if (RECONCILE_UPLOAD_PART.equals(task.getTaskType())) {
                if (task.getUploadId() == null
                        || !task.getUploadId().matches("^[0-9a-fA-F]{32}$")
                        || task.getPartNumber() == null
                        || task.getPartNumber() < 1) {
                    throw new IllegalStateException("reconciliation reference is invalid");
                }
                FileUploadPart reference = partMapper.selectOne(
                        new LambdaQueryWrapper<FileUploadPart>()
                                .eq(FileUploadPart::getUploadId, task.getUploadId())
                                .eq(FileUploadPart::getPartNumber, task.getPartNumber())
                                .last("LIMIT 1"));
                if (reference != null
                        && task.getObjectKey().equals(reference.getStoragePath())) {
                    // The object is live. Reconciliation is complete, but the
                    // referenced bytes must remain untouched.
                    taskMapper.deleteById(taskId);
                    return true;
                }
            }
            FileObjectStorage storage = storageRegistry.forType(task.getStorageType());
            if (storage.exists(task.getObjectKey())) storage.delete(task.getObjectKey());
            taskMapper.deleteById(taskId);
            return true;
        } catch (RuntimeException exception) {
            int attempts = Math.max(0, task.getAttempts() == null ? 0 : task.getAttempts()) + 1;
            task.setAttempts(attempts);
            task.setLastError(safeText(exception.getClass().getSimpleName()
                    + ":" + exception.getMessage(), 240, "cleanup failed"));
            long delaySeconds = Math.min(3600L, 30L << Math.min(7, attempts - 1));
            task.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds));
            task.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(task);
            log.warn("文件对象清理失败，将稍后重试: storage={}, key={}, attempt={}",
                    task.getStorageType(), task.getObjectKey(), attempts);
            return false;
        }
    }

    @Override
    public int retryPending() {
        List<FileObjectCleanupTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<FileObjectCleanupTask>()
                        .le(FileObjectCleanupTask::getNextRetryAt, LocalDateTime.now())
                        .orderByAsc(FileObjectCleanupTask::getNextRetryAt)
                        .last("LIMIT 100"));
        int cleaned = 0;
        for (FileObjectCleanupTask task : tasks) {
            if (attemptThroughProxy(task.getId())) cleaned++;
        }
        return cleaned;
    }

    private boolean attemptThroughProxy(Long taskId) {
        try {
            FileObjectCleanupService proxy = selfProvider.getIfAvailable();
            return proxy != null && proxy.attemptTask(taskId);
        } catch (RuntimeException exception) {
            log.warn("文件对象清理任务启动失败: taskId={}", taskId);
            return false;
        }
    }

    private String safeText(String value, int maximumLength, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String sanitized = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return sanitized.substring(0, Math.min(maximumLength, sanitized.length()));
    }
}
