package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lanchat.dto.FileCheckDTO;
import com.lanchat.dto.FileUploadVO;
import com.lanchat.dto.ResumableUploadInitDTO;
import com.lanchat.dto.ResumableUploadVO;
import com.lanchat.entity.FileMetadata;
import com.lanchat.entity.FileUploadPart;
import com.lanchat.entity.FileUploadSession;
import com.lanchat.mapper.FileUploadPartMapper;
import com.lanchat.mapper.FileUploadSessionMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.ConversationService;
import com.lanchat.service.FileObjectCleanupService;
import com.lanchat.service.FileService;
import com.lanchat.service.ResumableUploadService;
import com.lanchat.service.storage.FileObjectStorage;
import com.lanchat.service.storage.FileObjectStorageRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ResumableUploadServiceImpl implements ResumableUploadService {

    private static final Logger log = LoggerFactory.getLogger(ResumableUploadServiceImpl.class);
    private static final String UPLOADING = "UPLOADING";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELLED = "CANCELLED";
    private static final String EXPIRED = "EXPIRED";

    private final FileUploadSessionMapper sessionMapper;
    private final FileUploadPartMapper partMapper;
    private final FileService fileService;
    private final ConversationService conversationService;
    private final UserMapper userMapper;
    private final FileObjectStorageRegistry storageRegistry;
    private final FileObjectCleanupService cleanupService;
    private final TransactionTemplate transactionTemplate;

    @Value("${file.upload.chunk-size:5242880}")
    private long configuredChunkSize;

    @Value("${file.upload.ttl-hours:24}")
    private long uploadTtlHours;

    @Value("${file.upload.max-concurrency:4}")
    private int maximumConcurrentUploads;

    @Value("${file.max-size:104857600}")
    private long maximumFileSize;

    @Value("${file.path:./uploads/}")
    private String filePath;

    @Value("${file.staging-path:}")
    private String stagingPath;

    public ResumableUploadServiceImpl(FileUploadSessionMapper sessionMapper,
                                      FileUploadPartMapper partMapper,
                                      FileService fileService,
                                      ConversationService conversationService,
                                      UserMapper userMapper,
                                      FileObjectStorageRegistry storageRegistry,
                                      FileObjectCleanupService cleanupService,
                                      PlatformTransactionManager transactionManager) {
        this.sessionMapper = sessionMapper;
        this.partMapper = partMapper;
        this.fileService = fileService;
        this.conversationService = conversationService;
        this.userMapper = userMapper;
        this.storageRegistry = storageRegistry;
        this.cleanupService = cleanupService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional
    public ResumableUploadVO initialize(ResumableUploadInitDTO request, Long userId) {
        validateInitialization(request, userId);
        if (!conversationService.canUploadFile(request.getConversationId(), userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权在该会话发送文件");
        }

        // Every initialize path uses the same user-row lock order. In addition
        // to serializing COUNT -> INSERT, this prevents concurrent reactivation
        // of CANCELLED/EXPIRED/stale-COMPLETED sessions from bypassing quota.
        if (userMapper.lockById(userId) == null) {
            throw new IllegalArgumentException("上传用户不存在");
        }

        FileUploadSession existing = findByClientUploadId(userId, request.getClientUploadId());
        if (existing != null) {
            return reuseExisting(existing, request, userId);
        }

        requireAvailableUploadSlot(userId);

        FileUploadSession session = new FileUploadSession();
        session.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        session.setClientUploadId(request.getClientUploadId());
        session.setUserId(userId);
        session.setConversationId(request.getConversationId());
        session.setFileName(sanitizeFilename(request.getFileName()));
        session.setFileSize(request.getFileSize());
        session.setFileType(normalizeContentType(request.getFileType()));
        session.setFileHash(request.getFileHash().toLowerCase(Locale.ROOT));
        session.setChunkSize(effectiveChunkSize());
        session.setTotalParts(totalParts(request.getFileSize(), session.getChunkSize()));
        session.setStatus(UPLOADING);
        session.setStorageType(storageRegistry.activeType());
        session.setExpiresAt(nextExpiry());
        session.setCreateTime(LocalDateTime.now());
        session.setUpdateTime(session.getCreateTime());
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException duplicate) {
            FileUploadSession raced = findByClientUploadId(userId, request.getClientUploadId());
            if (raced == null) throw duplicate;
            verifySameRequest(raced, request);
            return toVO(raced, userId);
        }
        return toVO(session, userId);
    }

    @Override
    @Transactional
    public ResumableUploadVO status(String uploadId, Long userId) {
        FileUploadSession session = requireOwnedSession(uploadId, userId, true);
        if (!conversationService.canUploadFile(session.getConversationId(), userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该上传会话");
        }
        expireIfNecessary(session);
        return toVO(session, userId);
    }

    @Override
    public ResumableUploadVO uploadPart(String uploadId,
                                        int partNumber,
                                        String sha256,
                                        long contentLength,
                                        InputStream input,
                                        Long userId) {
        if (input == null) throw new IllegalArgumentException("分片内容不能为空");
        String expectedHash = normalizeHash(sha256, "分片哈希无效");
        AtomicBoolean expiredBeforeUpload = new AtomicBoolean(false);
        FileUploadSession session = transactionTemplate.execute(status -> {
            FileUploadSession locked = requireOwnedSession(uploadId, userId, true);
            if (expireIfNecessary(locked)) {
                expiredBeforeUpload.set(true);
                return locked;
            }
            requireWritable(locked);
            if (!conversationService.canUploadFile(locked.getConversationId(), userId)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "无权在该会话发送文件");
            }
            return locked;
        });
        if (expiredBeforeUpload.get()) throw expiredUploadException();
        if (session == null) throw new IllegalStateException("上传会话检查失败");
        if (partNumber < 1 || partNumber > session.getTotalParts()) {
            throw new IllegalArgumentException("分片序号无效");
        }
        long expectedSize = expectedPartSize(session, partNumber);
        if (contentLength >= 0 && contentLength != expectedSize) {
            throw new IllegalArgumentException("分片大小校验失败");
        }

        FileUploadPart current = findPart(uploadId, partNumber);
        FileObjectStorage storage = storageRegistry.forType(session.getStorageType());
        if (current != null) {
            if (!expectedHash.equalsIgnoreCase(current.getPartHash())
                    || !Objects.equals(current.getPartSize(), expectedSize)) {
                throw new IllegalArgumentException("该分片已存在且内容不同");
            }
            if (storage.exists(current.getStoragePath())) {
                AtomicBoolean expired = new AtomicBoolean(false);
                ResumableUploadVO existingResult = transactionTemplate.execute(status -> {
                    FileUploadSession locked = requireOwnedSession(uploadId, userId, true);
                    if (expireIfNecessary(locked)) {
                        expired.set(true);
                        return null;
                    }
                    requireWritable(locked);
                    if (!conversationService.canUploadFile(locked.getConversationId(), userId)) {
                        throw new org.springframework.security.access.AccessDeniedException(
                                "无权在该会话发送文件");
                    }
                    FileUploadPart persisted = findPart(uploadId, partNumber);
                    if (persisted == null
                            || !expectedHash.equalsIgnoreCase(persisted.getPartHash())
                            || !Objects.equals(persisted.getPartSize(), expectedSize)
                            || !storage.exists(persisted.getStoragePath())) {
                        throw new IllegalArgumentException("分片状态已变化，请重新上传");
                    }
                    touch(locked);
                    sessionMapper.updateById(locked);
                    return toVO(locked, userId);
                });
                if (expired.get()) throw expiredUploadException();
                if (existingResult == null) throw new IllegalStateException("分片状态保存失败");
                return existingResult;
            }
        }

        Path staged = stagePart(input, expectedSize, expectedHash);
        String objectKey = partObjectKey(uploadId, partNumber, expectedHash);
        AtomicBoolean objectReferenced = new AtomicBoolean(false);
        try {
            // Persist intent before object I/O. If the following DB transaction
            // has an uncertain outcome, the delayed reconciler checks the exact
            // part reference before deciding whether the object may be deleted.
            cleanupService.enqueuePartReconciliation(storage.type(), objectKey,
                    uploadId, partNumber, "UPLOAD_PART_RECONCILIATION");
            storage.put(objectKey, staged, "application/octet-stream");
            try {
                AtomicBoolean expired = new AtomicBoolean(false);
                ResumableUploadVO result = transactionTemplate.execute(status -> {
                    FileUploadSession locked = requireOwnedSession(uploadId, userId, true);
                    if (expireIfNecessary(locked)) {
                        expired.set(true);
                        return null;
                    }
                    requireWritable(locked);
                    if (!conversationService.canUploadFile(locked.getConversationId(), userId)) {
                        throw new org.springframework.security.access.AccessDeniedException(
                                "无权在该会话发送文件");
                    }
                    FileUploadPart raced = findPart(uploadId, partNumber);
                    if (raced != null) {
                        if (!expectedHash.equalsIgnoreCase(raced.getPartHash())
                                || !Objects.equals(raced.getPartSize(), expectedSize)) {
                            throw new IllegalArgumentException("该分片已存在且内容不同");
                        }
                        if (!storage.exists(raced.getStoragePath())) {
                            requireReconciliationClaim(storage, objectKey, uploadId, partNumber);
                            raced.setStoragePath(objectKey);
                            partMapper.updateById(raced);
                            objectReferenced.set(true);
                        }
                    } else {
                        requireReconciliationClaim(storage, objectKey, uploadId, partNumber);
                        raced = new FileUploadPart();
                        raced.setUploadId(uploadId);
                        raced.setPartNumber(partNumber);
                        raced.setPartSize(expectedSize);
                        raced.setPartHash(expectedHash);
                        raced.setStoragePath(objectKey);
                        raced.setCreateTime(LocalDateTime.now());
                        partMapper.insert(raced);
                        objectReferenced.set(true);
                    }
                    touch(locked);
                    sessionMapper.updateById(locked);
                    return toVO(locked, userId);
                });
                if (expired.get()) throw expiredUploadException();
                if (result == null) throw new IllegalStateException("分片状态保存失败");
                if (!objectReferenced.get()) {
                    enqueueObjectCleanup(storage, objectKey, "REDUNDANT_UPLOAD_PART");
                }
                return result;
            } catch (RuntimeException exception) {
                // The pre-written durable reconciliation task owns cleanup. It
                // will retain an exact live reference or delete orphaned bytes.
                throw exception;
            }
        } finally {
            deleteQuietly(staged);
        }
    }

    @Override
    public FileUploadVO complete(String uploadId, Long userId) {
        AtomicBoolean expired = new AtomicBoolean(false);
        FileUploadVO result = transactionTemplate.execute(status -> {
            FileUploadSession session = requireOwnedSession(uploadId, userId, true);
            if (COMPLETED.equals(session.getStatus())) {
                FileUploadVO completed = completedFile(session, userId);
                if (completed == null) throw new IllegalStateException("已完成文件元数据不存在");
                return completed;
            }
            if (expireIfNecessary(session)) {
                expired.set(true);
                return null;
            }
            requireWritable(session);
            if (!conversationService.canUploadFile(session.getConversationId(), userId)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "无权在该会话发送文件");
            }
            return completeLocked(session, uploadId, userId);
        });
        if (expired.get()) throw expiredUploadException();
        if (result == null) throw new IllegalStateException("文件合并状态保存失败");
        return result;
    }

    /**
     * Deliberately runs inside the programmatic transaction started by complete().
     * Keeping the session row locked prevents cancel/reset/expiry cleanup from
     * deleting part objects while they are streamed and verified. Splitting this
     * safely requires a durable COMPLETING lease/state and recovery protocol.
     */
    private FileUploadVO completeLocked(FileUploadSession session,
                                        String uploadId,
                                        Long userId) {
        List<FileUploadPart> parts = parts(uploadId);
        if (parts.size() != session.getTotalParts()) {
            throw new IllegalArgumentException("文件分片尚未上传完整");
        }
        for (int index = 0; index < parts.size(); index++) {
            FileUploadPart part = parts.get(index);
            if (!Objects.equals(part.getPartNumber(), index + 1)
                    || !Objects.equals(part.getPartSize(), expectedPartSize(session, index + 1))) {
                throw new IllegalArgumentException("文件分片状态不完整");
            }
        }

        FileObjectStorage storage = storageRegistry.forType(session.getStorageType());
        Path assembled = createStagingFile(".assemble-", ".tmp");
        try {
            MessageDigest digest = messageDigest();
            long assembledSize = 0;
            try (OutputStream output = Files.newOutputStream(assembled,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                for (FileUploadPart part : parts) {
                    if (!storage.exists(part.getStoragePath())) {
                        throw new IllegalArgumentException("文件分片已丢失，请重新上传");
                    }
                    try (InputStream partInput = storage.open(part.getStoragePath())) {
                        int read;
                        while ((read = partInput.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            assembledSize += read;
                            if (assembledSize > session.getFileSize()) {
                                throw new IllegalArgumentException("文件大小校验失败");
                            }
                            digest.update(buffer, 0, read);
                            output.write(buffer, 0, read);
                        }
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("文件分片合并失败", exception);
            }
            String assembledHash = HexFormat.of().formatHex(digest.digest());
            if (assembledSize != session.getFileSize()
                    || !assembledHash.equalsIgnoreCase(session.getFileHash())) {
                throw new IllegalArgumentException("文件完整性校验失败");
            }

            // This repeats SHA-256 and content-signature validation inside the final
            // file service before any metadata is committed.
            FileUploadVO completed = fileService.storeStagedFile(
                    assembled, session.getFileName(), session.getFileType(),
                    session.getFileSize(), session.getFileHash(), userId, false);
            FileMetadata metadata = fileService.getByHash(session.getFileHash());
            if (metadata == null) throw new IllegalStateException("文件元数据保存失败");
            session.setStatus(COMPLETED);
            session.setCompletedFileId(metadata.getId());
            session.setUpdateTime(LocalDateTime.now());
            sessionMapper.updateById(session);
            enqueuePartCleanup(storage, parts, "UPLOAD_COMPLETED");
            partMapper.deleteByUploadId(uploadId);
            return completed;
        } finally {
            deleteQuietly(assembled);
        }
    }

    @Override
    @Transactional
    public void cancel(String uploadId, Long userId) {
        FileUploadSession session = requireOwnedSession(uploadId, userId, true);
        if (!conversationService.canUploadFile(session.getConversationId(), userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该上传会话");
        }
        if (expireIfNecessary(session)) return;
        if (CANCELLED.equals(session.getStatus()) || EXPIRED.equals(session.getStatus())) return;
        if (COMPLETED.equals(session.getStatus())) {
            throw new IllegalArgumentException("已完成的上传不能取消");
        }
        List<FileUploadPart> parts = parts(uploadId);
        session.setStatus(CANCELLED);
        session.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(session);
        enqueuePartCleanup(storageRegistry.forType(session.getStorageType()), parts,
                "UPLOAD_CANCELLED");
        partMapper.deleteByUploadId(uploadId);
    }

    @Override
    @Transactional
    public int expireSessions() {
        List<FileUploadSession> candidates = sessionMapper.selectList(
                new LambdaQueryWrapper<FileUploadSession>()
                        .eq(FileUploadSession::getStatus, UPLOADING)
                        .le(FileUploadSession::getExpiresAt, LocalDateTime.now())
                        .orderByAsc(FileUploadSession::getId)
                        .last("LIMIT 100"));
        int expired = 0;
        for (FileUploadSession candidate : candidates) {
            FileUploadSession locked = sessionMapper.selectByUploadIdForUpdate(candidate.getUploadId());
            if (locked == null || !UPLOADING.equals(locked.getStatus())
                    || locked.getExpiresAt() == null
                    || locked.getExpiresAt().isAfter(LocalDateTime.now())) continue;
            expireSession(locked);
            expired++;
        }
        return expired;
    }

    private void validateInitialization(ResumableUploadInitDTO request, Long userId) {
        if (request == null || userId == null) throw new IllegalArgumentException("上传参数不能为空");
        if (!StringUtils.hasText(request.getClientUploadId())
                || !request.getClientUploadId().matches("^[A-Za-z0-9_-]{8,80}$")) {
            throw new IllegalArgumentException("客户端上传标识无效");
        }
        if (!StringUtils.hasText(request.getConversationId())
                || request.getConversationId().length() > 64) {
            throw new IllegalArgumentException("会话标识无效");
        }
        String filename = sanitizeFilename(request.getFileName());
        if (!fileService.isAllowedType(filename)) throw new IllegalArgumentException("不支持的文件类型");
        if (request.getFileSize() == null || request.getFileSize() <= 0
                || request.getFileSize() > maximumFileSize) {
            throw new IllegalArgumentException("文件大小超过限制");
        }
        normalizeHash(request.getFileHash(), "文件哈希无效");
    }

    private void verifySameRequest(FileUploadSession session, ResumableUploadInitDTO request) {
        if (!Objects.equals(session.getConversationId(), request.getConversationId())
                || !Objects.equals(session.getFileName(), sanitizeFilename(request.getFileName()))
                || !Objects.equals(session.getFileSize(), request.getFileSize())
                || !Objects.equals(session.getFileType(), normalizeContentType(request.getFileType()))
                || !session.getFileHash().equalsIgnoreCase(request.getFileHash())) {
            throw new IllegalArgumentException("客户端上传标识已用于其他文件");
        }
    }

    private FileUploadSession findByClientUploadId(Long userId, String clientUploadId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<FileUploadSession>()
                .eq(FileUploadSession::getUserId, userId)
                .eq(FileUploadSession::getClientUploadId, clientUploadId)
                .last("LIMIT 1"));
    }

    private ResumableUploadVO reuseExisting(FileUploadSession existing,
                                             ResumableUploadInitDTO request,
                                             Long userId) {
        FileUploadSession locked = sessionMapper.selectByUploadIdForUpdate(existing.getUploadId());
        if (locked == null) throw new IllegalArgumentException("上传会话不存在");
        verifySameRequest(locked, request);
        if (COMPLETED.equals(locked.getStatus())) {
            FileUploadVO completed = completedFile(locked, userId);
            if (completed != null) return toVO(locked, completed);

            // Message recall/account cleanup may remove file_metadata while the
            // stable client upload id is still retained. Re-open that session.
            requireAvailableUploadSlot(userId);
            resetSession(locked);
            return toVO(locked, userId);
        }
        if (UPLOADING.equals(locked.getStatus())
                && locked.getExpiresAt() != null
                && locked.getExpiresAt().isAfter(LocalDateTime.now())) {
            touch(locked);
            sessionMapper.updateById(locked);
            return toVO(locked, userId);
        }
        requireAvailableUploadSlot(userId);
        resetSession(locked);
        return toVO(locked, userId);
    }

    private void requireAvailableUploadSlot(Long userId) {
        long active = sessionMapper.selectCount(new LambdaQueryWrapper<FileUploadSession>()
                .eq(FileUploadSession::getUserId, userId)
                .eq(FileUploadSession::getStatus, UPLOADING)
                .gt(FileUploadSession::getExpiresAt, LocalDateTime.now()));
        if (active >= Math.max(1, maximumConcurrentUploads)) {
            throw new IllegalArgumentException("同时进行的文件上传数量已达上限");
        }
    }

    private void resetSession(FileUploadSession session) {
        List<FileUploadPart> oldParts = parts(session.getUploadId());
        FileObjectStorage storage = storageRegistry.forType(session.getStorageType());
        enqueuePartCleanup(storage, oldParts, "UPLOAD_RESET");
        partMapper.deleteByUploadId(session.getUploadId());
        session.setStatus(UPLOADING);
        session.setCompletedFileId(null);
        session.setStorageType(storageRegistry.activeType());
        session.setChunkSize(effectiveChunkSize());
        session.setTotalParts(totalParts(session.getFileSize(), session.getChunkSize()));
        touch(session);
        sessionMapper.updateById(session);
    }

    private FileUploadSession requireOwnedSession(String uploadId, Long userId, boolean lock) {
        if (uploadId == null || !uploadId.matches("^[0-9a-fA-F]{32}$") || userId == null) {
            throw new IllegalArgumentException("上传会话无效");
        }
        FileUploadSession session = lock
                ? sessionMapper.selectByUploadIdForUpdate(uploadId)
                : sessionMapper.selectOne(new LambdaQueryWrapper<FileUploadSession>()
                        .eq(FileUploadSession::getUploadId, uploadId).last("LIMIT 1"));
        if (session == null) throw new IllegalArgumentException("上传会话不存在");
        if (!userId.equals(session.getUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该上传会话");
        }
        return session;
    }

    private void requireWritable(FileUploadSession session) {
        if (!UPLOADING.equals(session.getStatus())) {
            throw new IllegalArgumentException("上传会话已结束");
        }
    }

    private boolean expireIfNecessary(FileUploadSession session) {
        if (UPLOADING.equals(session.getStatus()) && session.getExpiresAt() != null
                && !session.getExpiresAt().isAfter(LocalDateTime.now())) {
            expireSession(session);
            return true;
        }
        return false;
    }

    private IllegalArgumentException expiredUploadException() {
        return new IllegalArgumentException("上传会话已过期");
    }

    private void expireSession(FileUploadSession session) {
        List<FileUploadPart> currentParts = parts(session.getUploadId());
        session.setStatus(EXPIRED);
        session.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(session);
        enqueuePartCleanup(storageRegistry.forType(session.getStorageType()), currentParts,
                "UPLOAD_EXPIRED");
        partMapper.deleteByUploadId(session.getUploadId());
    }

    private FileUploadPart findPart(String uploadId, int partNumber) {
        return partMapper.selectOne(new LambdaQueryWrapper<FileUploadPart>()
                .eq(FileUploadPart::getUploadId, uploadId)
                .eq(FileUploadPart::getPartNumber, partNumber)
                .last("LIMIT 1"));
    }

    private List<FileUploadPart> parts(String uploadId) {
        return partMapper.selectList(new LambdaQueryWrapper<FileUploadPart>()
                .eq(FileUploadPart::getUploadId, uploadId)
                .orderByAsc(FileUploadPart::getPartNumber));
    }

    private ResumableUploadVO toVO(FileUploadSession session, Long userId) {
        List<Integer> uploaded = UPLOADING.equals(session.getStatus())
                ? parts(session.getUploadId()).stream()
                        .map(FileUploadPart::getPartNumber)
                        .filter(Objects::nonNull)
                        .toList()
                : List.of();
        return new ResumableUploadVO(session.getUploadId(), session.getStatus(),
                session.getChunkSize(), session.getTotalParts(), uploaded,
                session.getExpiresAt(), completedFile(session, userId));
    }

    private ResumableUploadVO toVO(FileUploadSession session, FileUploadVO completed) {
        return new ResumableUploadVO(session.getUploadId(), session.getStatus(),
                session.getChunkSize(), session.getTotalParts(), List.of(),
                session.getExpiresAt(), completed);
    }

    private FileUploadVO completedFile(FileUploadSession session, Long userId) {
        if (!COMPLETED.equals(session.getStatus())) return null;
        FileCheckDTO check = new FileCheckDTO();
        check.setFileHash(session.getFileHash());
        check.setFileName(session.getFileName());
        check.setFileSize(session.getFileSize());
        return fileService.checkFile(check, userId);
    }

    private Path stagePart(InputStream input, long expectedSize, String expectedHash) {
        Path temporary = createStagingFile(".part-", ".tmp");
        try {
            MessageDigest digest = messageDigest();
            long total = 0;
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    if (total > expectedSize) throw new IllegalArgumentException("分片大小校验失败");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (total != expectedSize) throw new IllegalArgumentException("分片大小校验失败");
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedHash)) {
                throw new IllegalArgumentException("分片哈希校验失败");
            }
            return temporary;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("分片写入失败", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            throw exception;
        }
    }

    private Path createStagingFile(String prefix, String suffix) {
        try {
            Path root = StringUtils.hasText(stagingPath)
                    ? Paths.get(stagingPath).toAbsolutePath().normalize()
                    : Paths.get(filePath).toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (!Files.isDirectory(root) || !Files.isWritable(root)) {
                throw new IllegalStateException("上传暂存目录不可写");
            }
            return Files.createTempFile(root, prefix, suffix);
        } catch (IOException exception) {
            throw new IllegalStateException("上传暂存目录不可用", exception);
        }
    }

    private void enqueuePartCleanup(FileObjectStorage storage,
                                    List<FileUploadPart> parts,
                                    String reason) {
        for (FileUploadPart part : parts) {
            enqueueObjectCleanup(storage, part.getStoragePath(), reason);
        }
    }

    private void enqueueObjectCleanup(FileObjectStorage storage, String key, String reason) {
        if (storage == null || key == null || key.isBlank()) return;
        cleanupService.enqueue(storage.type(), key, reason);
    }

    private void requireReconciliationClaim(FileObjectStorage storage,
                                             String objectKey,
                                             String uploadId,
                                             int partNumber) {
        if (!cleanupService.claimPartReference(
                storage.type(), objectKey, uploadId, partNumber)) {
            throw new IllegalStateException("分片对象清理状态已变化，请重新上传");
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("上传暂存文件清理失败: {}", path.getFileName());
        }
    }

    private void touch(FileUploadSession session) {
        session.setExpiresAt(nextExpiry());
        session.setUpdateTime(LocalDateTime.now());
    }

    private LocalDateTime nextExpiry() {
        return LocalDateTime.now().plusHours(Math.max(1, uploadTtlHours));
    }

    private long effectiveChunkSize() {
        return Math.max(256 * 1024L, Math.min(configuredChunkSize, 16 * 1024 * 1024L));
    }

    private int totalParts(long fileSize, long chunkSize) {
        long count = (fileSize + chunkSize - 1) / chunkSize;
        if (count <= 0 || count > 10_000) throw new IllegalArgumentException("文件分片数量无效");
        return (int) count;
    }

    private long expectedPartSize(FileUploadSession session, int partNumber) {
        if (partNumber < session.getTotalParts()) return session.getChunkSize();
        return session.getFileSize() - session.getChunkSize() * (session.getTotalParts() - 1L);
    }

    private String partObjectKey(String uploadId, int partNumber, String hash) {
        return "multipart/" + uploadId.toLowerCase(Locale.ROOT) + "/"
                + partNumber + "-" + hash.toLowerCase(Locale.ROOT) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".part";
    }

    private String sanitizeFilename(String filename) {
        if (!StringUtils.hasText(filename)) throw new IllegalArgumentException("文件名不能为空");
        String value = filename.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.isBlank() || value.equals(".") || value.equals("..") || value.length() > 180) {
            throw new IllegalArgumentException("文件名无效");
        }
        return value;
    }

    private String normalizeContentType(String value) {
        if (!StringUtils.hasText(value)) return "application/octet-stream";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || normalized.contains("\r") || normalized.contains("\n")) {
            throw new IllegalArgumentException("文件类型无效");
        }
        return normalized;
    }

    private String normalizeHash(String value, String error) {
        if (value == null || !value.matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(error);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
