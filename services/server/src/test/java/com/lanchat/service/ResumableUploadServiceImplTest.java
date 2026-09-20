package com.lanchat.service;

import com.lanchat.dto.ResumableUploadInitDTO;
import com.lanchat.entity.FileUploadPart;
import com.lanchat.entity.FileUploadSession;
import com.lanchat.mapper.FileUploadPartMapper;
import com.lanchat.mapper.FileUploadSessionMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.impl.ResumableUploadServiceImpl;
import com.lanchat.service.storage.FileObjectStorage;
import com.lanchat.service.storage.FileObjectStorageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResumableUploadServiceImplTest {

    private FileUploadSessionMapper sessionMapper;
    private FileUploadPartMapper partMapper;
    private ConversationService conversationService;
    private FileService fileService;
    private FileObjectCleanupService cleanupService;
    private UserMapper userMapper;
    private PlatformTransactionManager transactionManager;
    private FileObjectStorage storage;
    private ResumableUploadServiceImpl service;
    private FileUploadSession session;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(FileUploadSessionMapper.class);
        partMapper = mock(FileUploadPartMapper.class);
        conversationService = mock(ConversationService.class);
        fileService = mock(FileService.class);
        cleanupService = mock(FileObjectCleanupService.class);
        userMapper = mock(UserMapper.class);
        storage = mock(FileObjectStorage.class);
        when(storage.type()).thenReturn("LOCAL");
        FileObjectStorageRegistry registry = new FileObjectStorageRegistry(List.of(storage), "LOCAL");
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(
                invocation -> new SimpleTransactionStatus());
        service = new ResumableUploadServiceImpl(sessionMapper, partMapper,
                fileService, conversationService, userMapper, registry, cleanupService,
                transactionManager);
        ReflectionTestUtils.setField(service, "stagingPath", "");
        ReflectionTestUtils.setField(service, "filePath", System.getProperty("java.io.tmpdir"));
        ReflectionTestUtils.setField(service, "configuredChunkSize", 512 * 1024L);
        ReflectionTestUtils.setField(service, "uploadTtlHours", 24L);
        ReflectionTestUtils.setField(service, "maximumConcurrentUploads", 4);
        ReflectionTestUtils.setField(service, "maximumFileSize", 100 * 1024 * 1024L);
        when(fileService.isAllowedType(anyString())).thenReturn(true);
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(cleanupService.claimPartReference(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);

        session = new FileUploadSession();
        session.setUploadId("0123456789abcdef0123456789abcdef");
        session.setUserId(7L);
        session.setConversationId("private:7:8");
        session.setFileSize(3L);
        session.setChunkSize(5L);
        session.setTotalParts(1);
        session.setStatus("UPLOADING");
        session.setStorageType("LOCAL");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(sessionMapper.selectByUploadIdForUpdate(session.getUploadId())).thenReturn(session);
        when(conversationService.canUploadFile("private:7:8", 7L)).thenReturn(true);
    }

    @Test
    void partNumbersAreOneBased() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.uploadPart(session.getUploadId(), 0, "a".repeat(64), 3,
                        new ByteArrayInputStream(new byte[]{1, 2, 3}), 7L));

        assertTrue(error.getMessage().contains("分片序号"));
        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void aPersistedPartCannotBeOverwrittenWithDifferentBytes() {
        FileUploadPart existing = new FileUploadPart();
        existing.setUploadId(session.getUploadId());
        existing.setPartNumber(1);
        existing.setPartSize(3L);
        existing.setPartHash("b".repeat(64));
        existing.setStoragePath("multipart/old.part");
        when(partMapper.selectOne(any())).thenReturn(existing);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.uploadPart(session.getUploadId(), 1, "a".repeat(64), 3,
                        new ByteArrayInputStream(new byte[]{1, 2, 3}), 7L));

        assertTrue(error.getMessage().contains("内容不同"));
        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void verifiedPartIsPersistedWithOneBasedNumber() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        when(sessionMapper.selectByUploadIdForUpdate(session.getUploadId())).thenReturn(session);
        when(partMapper.selectList(any())).thenReturn(List.of());

        var response = service.uploadPart(session.getUploadId(), 1, hash, bytes.length,
                new ByteArrayInputStream(bytes), 7L);

        assertTrue(response.uploadedParts().isEmpty()); // mock mapper does not retain inserted rows
        verify(partMapper).insert(org.mockito.ArgumentMatchers.<FileUploadPart>argThat(part ->
                part.getPartNumber() == 1
                        && part.getPartSize() == 3
                        && hash.equals(part.getPartHash())));
        var durabilityOrder = inOrder(cleanupService, storage);
        durabilityOrder.verify(cleanupService).enqueuePartReconciliation(
                org.mockito.ArgumentMatchers.eq("LOCAL"), anyString(),
                org.mockito.ArgumentMatchers.eq(session.getUploadId()),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq("UPLOAD_PART_RECONCILIATION"));
        durabilityOrder.verify(storage).put(
                any(), any(), org.mockito.ArgumentMatchers.eq("application/octet-stream"));
    }

    @Test
    void completedSessionWithoutMetadataIsSafelyReopenedOnCurrentStorage() {
        FileObjectStorage minio = mock(FileObjectStorage.class);
        when(minio.type()).thenReturn("MINIO");
        FileObjectStorageRegistry registry = new FileObjectStorageRegistry(
                List.of(storage, minio), "MINIO");
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ResumableUploadServiceImpl(sessionMapper, partMapper,
                fileService, conversationService, userMapper, registry, cleanupService,
                transactions);
        ReflectionTestUtils.setField(service, "configuredChunkSize", 512 * 1024L);
        ReflectionTestUtils.setField(service, "uploadTtlHours", 12L);
        ReflectionTestUtils.setField(service, "maximumConcurrentUploads", 4);
        ReflectionTestUtils.setField(service, "maximumFileSize", 100 * 1024 * 1024L);

        session.setClientUploadId("client-upload-01");
        session.setFileName("report.pdf");
        session.setFileType("application/pdf");
        session.setFileHash("a".repeat(64));
        session.setFileSize(2L * 1024 * 1024);
        session.setChunkSize(1024L);
        session.setTotalParts(2048);
        session.setStatus("COMPLETED");
        session.setCompletedFileId(99L);
        session.setStorageType("LOCAL");
        FileUploadPart oldPart = new FileUploadPart();
        oldPart.setStoragePath("multipart/old.part");
        when(sessionMapper.selectByUploadIdForUpdate(session.getUploadId())).thenReturn(session);
        when(partMapper.selectList(any())).thenReturn(List.of(oldPart), List.of());
        when(fileService.checkFile(any(), org.mockito.ArgumentMatchers.eq(7L))).thenReturn(null);

        var result = service.initialize(initializationRequest(), 7L);

        assertEquals("UPLOADING", result.status());
        assertNull(result.completedFile());
        assertEquals("MINIO", session.getStorageType());
        assertEquals(512 * 1024L, session.getChunkSize());
        assertEquals(4, session.getTotalParts());
        assertNull(session.getCompletedFileId());
        assertTrue(session.getExpiresAt().isAfter(LocalDateTime.now().plusHours(11)));
        verify(cleanupService).enqueue("LOCAL", "multipart/old.part", "UPLOAD_RESET");
        verify(partMapper).deleteByUploadId(session.getUploadId());
        verify(sessionMapper).updateById(session);
    }

    @Test
    void newSessionLocksUserBeforeCheckingAndConsumingQuota() {
        when(sessionMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectCount(any())).thenReturn(0L);

        service.initialize(initializationRequest(), 7L);

        var order = inOrder(userMapper, sessionMapper);
        order.verify(userMapper).lockById(7L);
        order.verify(sessionMapper).selectCount(any());
        order.verify(sessionMapper).insert(org.mockito.ArgumentMatchers.<FileUploadSession>any());
    }

    @Test
    void reactivatingEndedSessionChecksQuotaWhileHoldingUserLock() {
        session.setClientUploadId("client-upload-01");
        session.setFileName("report.pdf");
        session.setFileType("application/pdf");
        session.setFileHash("a".repeat(64));
        session.setFileSize(2L * 1024 * 1024);
        session.setStatus("CANCELLED");
        when(sessionMapper.selectCount(any())).thenReturn(1L);
        ReflectionTestUtils.setField(service, "maximumConcurrentUploads", 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.initialize(initializationRequest(), 7L));

        assertTrue(error.getMessage().contains("已达上限"));
        var order = inOrder(userMapper, sessionMapper);
        order.verify(userMapper).lockById(7L);
        order.verify(sessionMapper).selectCount(any());
        verify(sessionMapper, never()).updateById(session);
        verify(partMapper, never()).deleteByUploadId(session.getUploadId());
    }

    @Test
    void expiredPartUploadCommitsExpiryAndCleanupBeforeReportingError() {
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        FileUploadPart oldPart = new FileUploadPart();
        oldPart.setStoragePath("multipart/expired.part");
        when(partMapper.selectList(any())).thenReturn(List.of(oldPart));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.uploadPart(session.getUploadId(), 1, "a".repeat(64), 3,
                        new ByteArrayInputStream(new byte[]{1, 2, 3}), 7L));

        assertTrue(error.getMessage().contains("已过期"));
        assertEquals("EXPIRED", session.getStatus());
        verify(sessionMapper).updateById(session);
        verify(partMapper).deleteByUploadId(session.getUploadId());
        verify(cleanupService).enqueue("LOCAL", "multipart/expired.part", "UPLOAD_EXPIRED");
        verify(transactionManager, atLeastOnce()).commit(any());
    }

    @Test
    void expiredCompleteCommitsExpiryBeforeReportingError() {
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        FileUploadPart oldPart = new FileUploadPart();
        oldPart.setStoragePath("multipart/complete-expired.part");
        when(partMapper.selectList(any())).thenReturn(List.of(oldPart));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.complete(session.getUploadId(), 7L));

        assertTrue(error.getMessage().contains("已过期"));
        assertEquals("EXPIRED", session.getStatus());
        verify(sessionMapper).updateById(session);
        verify(partMapper).deleteByUploadId(session.getUploadId());
        verify(cleanupService).enqueue(
                "LOCAL", "multipart/complete-expired.part", "UPLOAD_EXPIRED");
        verify(transactionManager, atLeastOnce()).commit(any());
        verify(storage, never()).open(anyString());
    }

    private ResumableUploadInitDTO initializationRequest() {
        ResumableUploadInitDTO request = new ResumableUploadInitDTO();
        request.setClientUploadId("client-upload-01");
        request.setConversationId("private:7:8");
        request.setFileName("report.pdf");
        request.setFileSize(2L * 1024 * 1024);
        request.setFileType("application/pdf");
        request.setFileHash("a".repeat(64));
        return request;
    }
}
