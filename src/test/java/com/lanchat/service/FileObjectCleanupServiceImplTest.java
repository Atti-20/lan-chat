package com.lanchat.service;

import com.lanchat.entity.FileObjectCleanupTask;
import com.lanchat.entity.FileUploadPart;
import com.lanchat.mapper.FileObjectCleanupTaskMapper;
import com.lanchat.mapper.FileUploadPartMapper;
import com.lanchat.service.impl.FileObjectCleanupServiceImpl;
import com.lanchat.service.impl.FileServiceImpl;
import com.lanchat.service.storage.FileObjectStorage;
import com.lanchat.service.storage.FileObjectStorageRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileObjectCleanupServiceImplTest {

    private FileObjectCleanupTaskMapper taskMapper;
    private FileUploadPartMapper partMapper;
    private FileObjectStorage storage;
    private ObjectProvider<FileObjectCleanupService> selfProvider;
    private FileObjectCleanupServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskMapper = mock(FileObjectCleanupTaskMapper.class);
        partMapper = mock(FileUploadPartMapper.class);
        storage = mock(FileObjectStorage.class);
        when(storage.type()).thenReturn("LOCAL");
        selfProvider = mock(ObjectProvider.class);
        service = new FileObjectCleanupServiceImpl(taskMapper, partMapper,
                new FileObjectStorageRegistry(List.of(storage), "LOCAL"), selfProvider);
        when(selfProvider.getIfAvailable()).thenReturn(service);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void enqueueWaitsForCommitBeforeDeletingPhysicalObject() {
        doAnswer(invocation -> {
            FileObjectCleanupTask task = invocation.getArgument(0);
            task.setId(41L);
            return 1;
        }).when(taskMapper).enqueue(any());
        FileObjectCleanupTask persisted = task(41L, "files/unused.bin");
        when(taskMapper.selectByIdForUpdate(41L)).thenReturn(persisted);
        when(storage.exists("files/unused.bin")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        service.enqueue("LOCAL", "files/unused.bin", "FILE_METADATA_REMOVED");

        verify(storage, never()).delete(any());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
        verify(storage).delete("files/unused.bin");
        verify(taskMapper).deleteById(41L);
    }

    @Test
    void failedPhysicalDeletionRemainsQueuedWithBackoff() {
        FileObjectCleanupTask persisted = task(42L, "files/retry.bin");
        when(taskMapper.selectByIdForUpdate(42L)).thenReturn(persisted);
        when(storage.exists("files/retry.bin")).thenThrow(new IllegalStateException("offline"));

        boolean cleaned = service.attemptTask(42L);

        assertFalse(cleaned);
        verify(taskMapper, never()).deleteById(42L);
        verify(taskMapper).updateById(org.mockito.ArgumentMatchers.<FileObjectCleanupTask>argThat(task ->
                task.getAttempts() == 1
                        && task.getNextRetryAt().isAfter(LocalDateTime.now())
                        && task.getLastError().contains("offline")));
    }

    @Test
    void missingPhysicalObjectCompletesCleanupTaskIdempotently() {
        FileObjectCleanupTask persisted = task(43L, "files/already-gone.bin");
        when(taskMapper.selectByIdForUpdate(43L)).thenReturn(persisted);
        when(storage.exists("files/already-gone.bin")).thenReturn(false);

        assertTrue(service.attemptTask(43L));

        verify(storage, never()).delete(any());
        verify(taskMapper).deleteById(43L);
    }

    @Test
    void uploadRollbackUsesDetachedCleanupTransactionEntryPoint() {
        FileObjectCleanupService cleanup = mock(FileObjectCleanupService.class);
        FileServiceImpl fileService = new FileServiceImpl();
        ReflectionTestUtils.setField(fileService, "objectCleanupService", cleanup);
        TransactionSynchronizationManager.initSynchronization();
        ReflectionTestUtils.invokeMethod(fileService,
                "deleteObjectOnTransactionRollback", storage,
                (Object) new String[]{"files/rollback.bin"});

        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(cleanup).enqueueDetached("LOCAL", "files/rollback.bin", "UPLOAD_ROLLBACK");
        verify(cleanup, never()).enqueue(any(), any(), any());
    }

    @Test
    void referencedPartReconciliationNeverDeletesLiveObject() {
        FileObjectCleanupTask persisted = task(44L, "multipart/live.part");
        persisted.setTaskType("RECONCILE_UPLOAD_PART");
        persisted.setUploadId("0123456789abcdef0123456789abcdef");
        persisted.setPartNumber(1);
        FileUploadPart reference = new FileUploadPart();
        reference.setStoragePath("multipart/live.part");
        when(taskMapper.selectByIdForUpdate(44L)).thenReturn(persisted);
        when(partMapper.selectOne(any())).thenReturn(reference);

        assertTrue(service.attemptTask(44L));

        verify(storage, never()).exists(any());
        verify(storage, never()).delete(any());
        verify(taskMapper).deleteById(44L);
    }

    @Test
    void failedReferenceLookupKeepsReconciliationTaskForRetry() {
        FileObjectCleanupTask persisted = task(45L, "multipart/uncertain.part");
        persisted.setTaskType("RECONCILE_UPLOAD_PART");
        persisted.setUploadId("0123456789abcdef0123456789abcdef");
        persisted.setPartNumber(2);
        when(taskMapper.selectByIdForUpdate(45L)).thenReturn(persisted);
        when(partMapper.selectOne(any())).thenThrow(new IllegalStateException("database offline"));

        assertFalse(service.attemptTask(45L));

        verify(storage, never()).exists(any());
        verify(storage, never()).delete(any());
        verify(taskMapper, never()).deleteById(45L);
        verify(taskMapper).updateById(org.mockito.ArgumentMatchers.<FileObjectCleanupTask>argThat(task ->
                task.getAttempts() == 1 && task.getLastError().contains("database offline")));
    }

    @Test
    void partReconciliationIntentIsDelayedAndNotDeletedImmediately() {
        service.enqueuePartReconciliation("LOCAL", "multipart/planned.part",
                "0123456789abcdef0123456789abcdef", 3, "UPLOAD_PART_RECONCILIATION");

        verify(taskMapper).enqueue(org.mockito.ArgumentMatchers.<FileObjectCleanupTask>argThat(task ->
                "RECONCILE_UPLOAD_PART".equals(task.getTaskType())
                        && task.getPartNumber() == 3
                        && task.getNextRetryAt().isAfter(LocalDateTime.now().plusMinutes(4))));
        verify(storage, never()).exists(any());
        verify(storage, never()).delete(any());
    }

    @Test
    void claimPartReferenceResolvesIntentInsideCallerTransaction() {
        FileObjectCleanupTask persisted = task(46L, "multipart/claimed.part");
        persisted.setTaskType("RECONCILE_UPLOAD_PART");
        persisted.setUploadId("0123456789abcdef0123456789abcdef");
        persisted.setPartNumber(4);
        when(taskMapper.selectByObjectForUpdate("LOCAL", "multipart/claimed.part"))
                .thenReturn(persisted);

        assertTrue(service.claimPartReference("LOCAL", "multipart/claimed.part",
                persisted.getUploadId(), 4));

        verify(taskMapper).deleteById(46L);
        verify(storage, never()).delete(any());
    }

    private FileObjectCleanupTask task(Long id, String key) {
        FileObjectCleanupTask task = new FileObjectCleanupTask();
        task.setId(id);
        task.setStorageType("LOCAL");
        task.setObjectKey(key);
        task.setReason("TEST");
        task.setTaskType("DELETE");
        task.setAttempts(0);
        task.setNextRetryAt(LocalDateTime.now());
        return task;
    }
}
