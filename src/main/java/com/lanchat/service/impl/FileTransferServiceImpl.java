package com.lanchat.service.impl;

import com.lanchat.common.ConversationIds;
import com.lanchat.common.FileContentInspector;
import com.lanchat.dto.FileTransferCompletionDTO;
import com.lanchat.dto.FileTransferOfferDTO;
import com.lanchat.dto.FileTransferRelayCompletionDTO;
import com.lanchat.dto.FileTransferRoute;
import com.lanchat.dto.FileTransferVO;
import com.lanchat.entity.FileTransfer;
import com.lanchat.entity.FileTransferPath;
import com.lanchat.entity.FileTransferStatus;
import com.lanchat.mapper.FileTransferMapper;
import com.lanchat.service.FileTransferService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class FileTransferServiceImpl implements FileTransferService {

    private static final String DEFAULT_MEDIA_TYPE = "application/octet-stream";
    private static final String DEFAULT_ALLOWED_TYPES =
            "jpg,jpeg,png,gif,bmp,webp,doc,docx,xls,xlsx,ppt,pptx,pdf,txt,csv,json,md,"
                    + "zip,rar,7z,tar,gz,mp4,mp3,wav,avi,mov";

    private final FileTransferMapper fileTransferMapper;

    @Value("${file.max-size:104857600}")
    private long maximumFileSize;

    @Value("${file.transfer.direct-max-size:104857600}")
    private long maximumDirectFileSize;

    @Value("${file.transfer.offer-ttl-seconds:120}")
    private long offerTtlSeconds;

    @Value("${file.transfer.relay-ttl-seconds:3600}")
    private long relayTtlSeconds;

    @Value("${file.allowed-types:" + DEFAULT_ALLOWED_TYPES + "}")
    private String allowedTypes;

    public FileTransferServiceImpl(FileTransferMapper fileTransferMapper) {
        this.fileTransferMapper = fileTransferMapper;
    }

    @Override
    @Transactional
    public FileTransferVO createOffer(Long senderUserId,
                                      Long senderDeviceId,
                                      FileTransferOfferDTO request) {
        requirePositive(senderUserId, "发送用户");
        requirePositive(senderDeviceId, "发送设备");
        if (request == null) throw new IllegalArgumentException("文件传输参数不能为空");

        String clientTransferId = normalizeClientTransferId(request.clientTransferId());
        String conversationId = normalizeConversationId(request.conversationId());
        ConversationIds.PrivateParticipants participants = ConversationIds.parsePrivate(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("WebRTC 直传仅支持私聊会话"));
        if (!participants.contains(senderUserId)) {
            throw new IllegalArgumentException("发送用户不属于目标会话");
        }
        Long receiverUserId = participants.peerOf(senderUserId);

        String fileName = normalizeFileName(request.fileName());
        validateAllowedType(fileName);
        long fileSize = normalizeFileSize(request.fileSize());
        String fileType = normalizeMediaType(request.fileType());
        String fileHash = normalizeHash(request.fileHash());

        FileTransfer existing = fileTransferMapper.selectBySenderAndClientTransferId(
                senderUserId, clientTransferId);
        if (existing != null) {
            return toView(requireMatchingRetry(existing, senderDeviceId, conversationId,
                    receiverUserId, fileName, fileSize, fileType, fileHash));
        }

        LocalDateTime now = LocalDateTime.now();
        boolean relayRequired = fileSize > effectiveDirectLimit();
        FileTransfer transfer = new FileTransfer();
        transfer.setTransferId(UUID.randomUUID().toString().replace("-", ""));
        transfer.setClientTransferId(clientTransferId);
        transfer.setConversationId(conversationId);
        transfer.setSenderUserId(senderUserId);
        transfer.setSenderDeviceId(senderDeviceId);
        transfer.setReceiverUserId(receiverUserId);
        transfer.setFileName(fileName);
        transfer.setFileSize(fileSize);
        transfer.setFileType(fileType);
        transfer.setFileHash(fileHash);
        transfer.setStatus((relayRequired
                ? FileTransferStatus.RELAY_PENDING
                : FileTransferStatus.OFFERED).name());
        transfer.setTransportPath((relayRequired
                ? FileTransferPath.NODE_RELAY
                : FileTransferPath.PENDING).name());
        transfer.setFallbackReason(relayRequired ? "DIRECT_SIZE_LIMIT" : null);
        transfer.setExpiresAt(now.plusSeconds(relayRequired ? effectiveRelayTtl() : effectiveOfferTtl()));
        transfer.setCreateTime(now);
        transfer.setUpdateTime(now);

        try {
            if (fileTransferMapper.insert(transfer) != 1) {
                throw new IllegalStateException("文件传输任务创建失败");
            }
        } catch (DuplicateKeyException duplicate) {
            FileTransfer raced = fileTransferMapper.selectBySenderAndClientTransferId(
                    senderUserId, clientTransferId);
            if (raced == null) throw duplicate;
            return toView(requireMatchingRetry(raced, senderDeviceId, conversationId,
                    receiverUserId, fileName, fileSize, fileType, fileHash));
        }
        return toView(transfer);
    }

    @Override
    public FileTransferVO getForParticipant(String transferId, Long userId, Long deviceId) {
        requirePositive(userId, "用户");
        requirePositive(deviceId, "设备");
        FileTransfer transfer = requireCurrentTransfer(transferId);
        requireParticipantDevice(transfer, userId, deviceId, true);
        return toView(transfer);
    }

    @Override
    @Transactional
    public FileTransferVO claimReceiverDevice(String transferId,
                                              Long receiverUserId,
                                              Long receiverDeviceId) {
        requirePositive(receiverUserId, "接收用户");
        requirePositive(receiverDeviceId, "接收设备");
        FileTransfer transfer = requireCurrentTransfer(transferId);
        if (!receiverUserId.equals(transfer.getReceiverUserId())) {
            throw new IllegalArgumentException("当前用户不是文件接收者");
        }

        if (transfer.getReceiverDeviceId() != null) {
            if (receiverDeviceId.equals(transfer.getReceiverDeviceId())
                    && isClaimedOrLaterPeerState(statusOf(transfer))) {
                return toView(transfer);
            }
            throw new IllegalArgumentException("文件传输已由其他设备接收");
        }
        if (statusOf(transfer) != FileTransferStatus.OFFERED) {
            throw new IllegalArgumentException("当前文件传输不可认领");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = fileTransferMapper.claimReceiverDevice(
                transfer.getTransferId(), receiverUserId, receiverDeviceId, now);
        FileTransfer current = requireStoredTransfer(transfer.getTransferId());
        if (updated == 1 || (receiverDeviceId.equals(current.getReceiverDeviceId())
                && isClaimedOrLaterPeerState(statusOf(current)))) {
            return toView(current);
        }
        if (current.getReceiverDeviceId() != null) {
            throw new IllegalArgumentException("文件传输已由其他设备接收");
        }
        throw new IllegalArgumentException("文件传输认领失败或已经过期");
    }

    @Override
    public FileTransferRoute authorizePeerSignal(String transferId,
                                                 Long actorUserId,
                                                 Long actorDeviceId) {
        requirePositive(actorUserId, "信令用户");
        requirePositive(actorDeviceId, "信令设备");
        FileTransfer transfer = requireCurrentTransfer(transferId);
        if (!statusOf(transfer).isPeerActive() || transfer.getReceiverDeviceId() == null) {
            throw new IllegalArgumentException("文件传输尚未建立点对点路由");
        }

        if (isSenderDevice(transfer, actorUserId, actorDeviceId)) {
            return new FileTransferRoute(
                    transfer.getTransferId(),
                    transfer.getConversationId(),
                    transfer.getReceiverUserId(),
                    transfer.getReceiverDeviceId(),
                    transfer.getStatus()
            );
        }
        if (isReceiverDevice(transfer, actorUserId, actorDeviceId)) {
            return new FileTransferRoute(
                    transfer.getTransferId(),
                    transfer.getConversationId(),
                    transfer.getSenderUserId(),
                    transfer.getSenderDeviceId(),
                    transfer.getStatus()
            );
        }
        throw new IllegalArgumentException("当前设备无权转发该文件信令");
    }

    @Override
    @Transactional
    public FileTransferVO markNegotiating(String transferId,
                                          Long senderUserId,
                                          Long senderDeviceId) {
        requirePositive(senderUserId, "发送用户");
        requirePositive(senderDeviceId, "发送设备");
        FileTransfer transfer = requireCurrentTransfer(transferId);
        if (!isSenderDevice(transfer, senderUserId, senderDeviceId)) {
            throw new IllegalArgumentException("只有发起设备可以开始协商");
        }
        FileTransferStatus status = statusOf(transfer);
        if (status == FileTransferStatus.NEGOTIATING || status == FileTransferStatus.TRANSFERRING) {
            return toView(transfer);
        }
        if (status != FileTransferStatus.CLAIMED) {
            throw new IllegalArgumentException("文件传输当前不可开始协商");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = fileTransferMapper.markNegotiating(
                transfer.getTransferId(), senderUserId, senderDeviceId, now);
        FileTransfer current = requireStoredTransfer(transfer.getTransferId());
        if (updated == 1 || statusOf(current) == FileTransferStatus.NEGOTIATING
                || statusOf(current) == FileTransferStatus.TRANSFERRING) {
            return toView(current);
        }
        throw new IllegalArgumentException("文件传输状态已经变化");
    }

    @Override
    @Transactional
    public FileTransferVO markTransferring(String transferId,
                                           Long actorUserId,
                                           Long actorDeviceId) {
        authorizePeerSignal(transferId, actorUserId, actorDeviceId);
        FileTransfer transfer = requireCurrentTransfer(transferId);
        if (statusOf(transfer) == FileTransferStatus.TRANSFERRING) return toView(transfer);

        LocalDateTime now = LocalDateTime.now();
        int updated = fileTransferMapper.markTransferring(transfer.getTransferId(), now);
        FileTransfer current = requireStoredTransfer(transfer.getTransferId());
        if (updated == 1 || statusOf(current) == FileTransferStatus.TRANSFERRING) {
            return toView(current);
        }
        throw new IllegalArgumentException("文件传输状态已经变化");
    }

    @Override
    @Transactional
    public FileTransferVO completePeerToPeer(String transferId,
                                             Long receiverUserId,
                                             Long receiverDeviceId,
                                             FileTransferCompletionDTO completion) {
        requirePositive(receiverUserId, "接收用户");
        requirePositive(receiverDeviceId, "接收设备");
        if (completion == null) throw new IllegalArgumentException("文件完成参数不能为空");
        String fileHash = normalizeHash(completion.fileHash());
        long fileSize = normalizeFileSize(completion.fileSize());

        FileTransfer transfer = requireCurrentTransfer(transferId);
        if (!isReceiverDevice(transfer, receiverUserId, receiverDeviceId)) {
            throw new IllegalArgumentException("只有已认领的接收设备可以确认完成");
        }
        if (!fileHash.equals(transfer.getFileHash()) || fileSize != transfer.getFileSize()) {
            throw new IllegalArgumentException("接收文件完整性校验失败");
        }
        if (statusOf(transfer) == FileTransferStatus.P2P_COMPLETED) return toView(transfer);
        if (!statusOf(transfer).isPeerActive()) {
            throw new IllegalArgumentException("文件传输当前不可确认完成");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = fileTransferMapper.completePeerToPeer(
                transfer.getTransferId(), receiverUserId, receiverDeviceId,
                fileHash, fileSize, now);
        FileTransfer current = requireStoredTransfer(transfer.getTransferId());
        if (updated == 1 || statusOf(current) == FileTransferStatus.P2P_COMPLETED) {
            if (!fileHash.equals(current.getFileHash()) || fileSize != current.getFileSize()) {
                throw new IllegalArgumentException("接收文件完整性校验失败");
            }
            return toView(current);
        }
        throw new IllegalArgumentException("文件传输状态已经变化");
    }

    @Override
    @Transactional
    public FileTransferVO fallbackToNodeRelay(String transferId,
                                              Long actorUserId,
                                              Long actorDeviceId,
                                              String reason) {
        requirePositive(actorUserId, "操作用户");
        requirePositive(actorDeviceId, "操作设备");
        FileTransfer transfer = requireCurrentTransfer(transferId);
        requireParticipantDevice(transfer, actorUserId, actorDeviceId, false);
        FileTransferStatus status = statusOf(transfer);
        if (status == FileTransferStatus.RELAY_PENDING || status == FileTransferStatus.RELAY_COMPLETED) {
            return toView(transfer);
        }
        if (status == FileTransferStatus.P2P_COMPLETED) {
            throw new IllegalArgumentException("点对点文件已经传输完成");
        }
        if (!status.isActive()) {
            throw new IllegalArgumentException("文件传输当前不可降级");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = fileTransferMapper.fallbackToNodeRelay(
                transfer.getTransferId(), normalizeFallbackReason(reason),
                now.plusSeconds(effectiveRelayTtl()), now);
        FileTransfer current = requireStoredTransfer(transfer.getTransferId());
        if (updated == 1 || statusOf(current) == FileTransferStatus.RELAY_PENDING
                || statusOf(current) == FileTransferStatus.RELAY_COMPLETED) {
            return toView(current);
        }
        throw new IllegalArgumentException("文件传输状态已经变化");
    }

    @Override
    @Transactional
    public FileTransferVO completeNodeRelay(String transferId,
                                            Long senderUserId,
                                            Long senderDeviceId,
                                            FileTransferRelayCompletionDTO completion) {
        requirePositive(senderUserId, "发送用户");
        requirePositive(senderDeviceId, "发送设备");
        if (completion == null) throw new IllegalArgumentException("节点中转完成参数不能为空");
        requirePositive(completion.fileMetadataId(), "文件元数据");
        String storedFileName = normalizeStoredFileName(completion.storedFileName());
        String fileHash = normalizeHash(completion.fileHash());
        long fileSize = normalizeFileSize(completion.fileSize());

        FileTransfer transfer = requireCurrentTransfer(transferId);
        if (!isSenderDevice(transfer, senderUserId, senderDeviceId)) {
            throw new IllegalArgumentException("只有发起设备可以绑定节点文件");
        }
        if (!fileHash.equals(transfer.getFileHash()) || fileSize != transfer.getFileSize()) {
            throw new IllegalArgumentException("节点文件与传输任务不一致");
        }
        if (statusOf(transfer) == FileTransferStatus.RELAY_COMPLETED) {
            if (completion.fileMetadataId().equals(transfer.getFileMetadataId())
                    && storedFileName.equals(transfer.getStoredFileName())) {
                return toView(transfer);
            }
            throw new IllegalArgumentException("节点中转任务已经绑定其他文件");
        }
        if (statusOf(transfer) != FileTransferStatus.RELAY_PENDING) {
            throw new IllegalArgumentException("文件传输当前不等待节点中转");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = fileTransferMapper.completeNodeRelay(
                transfer.getTransferId(), senderUserId, senderDeviceId,
                completion.fileMetadataId(), storedFileName, fileHash, fileSize, now);
        FileTransfer current = requireStoredTransfer(transfer.getTransferId());
        if (updated == 1 || statusOf(current) == FileTransferStatus.RELAY_COMPLETED) {
            if (!completion.fileMetadataId().equals(current.getFileMetadataId())
                    || !storedFileName.equals(current.getStoredFileName())) {
                throw new IllegalArgumentException("节点中转任务已经绑定其他文件");
            }
            return toView(current);
        }
        throw new IllegalArgumentException("文件传输状态已经变化");
    }

    @Override
    public FileTransferVO requireCompletedAttachment(String transferId,
                                                     String conversationId,
                                                     Long senderUserId,
                                                     Long senderDeviceId) {
        requirePositive(senderUserId, "发送用户");
        requirePositive(senderDeviceId, "发送设备");
        FileTransfer transfer = requireStoredTransfer(normalizeTransferId(transferId));
        if (!normalizeConversationId(conversationId).equals(transfer.getConversationId())
                || !isSenderDevice(transfer, senderUserId, senderDeviceId)) {
            throw new IllegalArgumentException("文件传输与消息会话或发送设备不一致");
        }
        FileTransferStatus status = statusOf(transfer);
        if (!status.isCompleted()) {
            throw new IllegalArgumentException("文件传输尚未完成");
        }
        if (status == FileTransferStatus.RELAY_COMPLETED
                && (transfer.getFileMetadataId() == null
                || !StringUtils.hasText(transfer.getStoredFileName()))) {
            throw new IllegalStateException("节点中转文件记录不完整");
        }
        return toView(transfer);
    }

    @Override
    public int expirePendingTransfers() {
        return fileTransferMapper.expirePending(LocalDateTime.now());
    }

    private FileTransfer requireCurrentTransfer(String rawTransferId) {
        String transferId = normalizeTransferId(rawTransferId);
        FileTransfer transfer = requireStoredTransfer(transferId);
        FileTransferStatus status = statusOf(transfer);
        if (status.isActive() && transfer.getExpiresAt() != null
                && !transfer.getExpiresAt().isAfter(LocalDateTime.now())) {
            fileTransferMapper.expireOne(transferId, LocalDateTime.now());
            throw new IllegalArgumentException("文件传输已经过期");
        }
        return transfer;
    }

    private FileTransfer requireStoredTransfer(String transferId) {
        FileTransfer transfer = fileTransferMapper.selectByTransferId(transferId);
        if (transfer == null) throw new IllegalArgumentException("文件传输不存在");
        return transfer;
    }

    private void requireParticipantDevice(FileTransfer transfer,
                                          Long userId,
                                          Long deviceId,
                                          boolean allowUnclaimedReceiver) {
        if (isSenderDevice(transfer, userId, deviceId)) return;
        if (userId.equals(transfer.getReceiverUserId())) {
            if (transfer.getReceiverDeviceId() == null && allowUnclaimedReceiver) return;
            if (deviceId.equals(transfer.getReceiverDeviceId())) return;
        }
        throw new IllegalArgumentException("当前设备无权访问该文件传输");
    }

    private boolean isSenderDevice(FileTransfer transfer, Long userId, Long deviceId) {
        return userId != null && deviceId != null
                && userId.equals(transfer.getSenderUserId())
                && deviceId.equals(transfer.getSenderDeviceId());
    }

    private boolean isReceiverDevice(FileTransfer transfer, Long userId, Long deviceId) {
        return userId != null && deviceId != null
                && userId.equals(transfer.getReceiverUserId())
                && deviceId.equals(transfer.getReceiverDeviceId());
    }

    private boolean isClaimedOrLaterPeerState(FileTransferStatus status) {
        return status == FileTransferStatus.CLAIMED
                || status == FileTransferStatus.NEGOTIATING
                || status == FileTransferStatus.TRANSFERRING
                || status == FileTransferStatus.P2P_COMPLETED;
    }

    private FileTransfer requireMatchingRetry(FileTransfer existing,
                                              Long senderDeviceId,
                                              String conversationId,
                                              Long receiverUserId,
                                              String fileName,
                                              long fileSize,
                                              String fileType,
                                              String fileHash) {
        if (!senderDeviceId.equals(existing.getSenderDeviceId())
                || !conversationId.equals(existing.getConversationId())
                || !receiverUserId.equals(existing.getReceiverUserId())
                || !fileName.equals(existing.getFileName())
                || !Long.valueOf(fileSize).equals(existing.getFileSize())
                || !fileType.equals(existing.getFileType())
                || !fileHash.equals(existing.getFileHash())) {
            throw new IllegalArgumentException("clientTransferId 已被其他文件任务使用");
        }
        return existing;
    }

    private FileTransferStatus statusOf(FileTransfer transfer) {
        return FileTransferStatus.fromStoredValue(transfer.getStatus());
    }

    private String normalizeTransferId(String transferId) {
        if (!StringUtils.hasText(transferId)
                || !transferId.trim().matches("(?i)^[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("transferId 无效");
        }
        return transferId.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeClientTransferId(String clientTransferId) {
        if (!StringUtils.hasText(clientTransferId)
                || !clientTransferId.trim().matches("^[A-Za-z0-9_-]{8,64}$")) {
            throw new IllegalArgumentException("clientTransferId 无效");
        }
        return clientTransferId.trim();
    }

    private String normalizeConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId) || conversationId.trim().length() > 64) {
            throw new IllegalArgumentException("会话标识无效");
        }
        return conversationId.trim();
    }

    private String normalizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) throw new IllegalArgumentException("文件名不能为空");
        String value = fileName.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (value.isBlank() || value.equals(".") || value.equals("..") || value.length() > 180) {
            throw new IllegalArgumentException("文件名无效或过长");
        }
        return value;
    }

    private long normalizeFileSize(Long fileSize) {
        long configuredMaximum = Math.max(1, maximumFileSize);
        if (fileSize == null || fileSize <= 0 || fileSize > configuredMaximum) {
            throw new IllegalArgumentException("文件大小无效或超过限制");
        }
        return fileSize;
    }

    private String normalizeMediaType(String mediaType) {
        if (!StringUtils.hasText(mediaType)) return DEFAULT_MEDIA_TYPE;
        String value = mediaType.replaceAll("[\\p{Cntrl}]", "").trim().toLowerCase(Locale.ROOT);
        if (value.length() > 120
                || !value.matches("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$")) {
            throw new IllegalArgumentException("文件媒体类型无效");
        }
        return value;
    }

    private String normalizeHash(String fileHash) {
        if (!StringUtils.hasText(fileHash) || !fileHash.trim().matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("文件 SHA-256 无效");
        }
        return fileHash.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStoredFileName(String storedFileName) {
        if (!StringUtils.hasText(storedFileName)
                || !storedFileName.trim().matches("(?i)^[0-9a-f]{32}\\.[a-z0-9]{1,10}$")) {
            throw new IllegalArgumentException("节点存储文件名无效");
        }
        return storedFileName.trim();
    }

    private String normalizeFallbackReason(String reason) {
        if (!StringUtils.hasText(reason)) return "P2P_UNAVAILABLE";
        String value = reason.trim().toUpperCase(Locale.ROOT);
        return value.matches("^[A-Z0-9_:-]{1,64}$") ? value : "P2P_FAILED";
    }

    private void validateAllowedType(String fileName) {
        String extension = FileContentInspector.extensionOf(fileName);
        if (extension == null || !allowedExtensionSet().contains(extension.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("不支持的文件类型");
        }
    }

    private Set<String> allowedExtensionSet() {
        String configured = StringUtils.hasText(allowedTypes) ? allowedTypes : DEFAULT_ALLOWED_TYPES;
        Set<String> values = new HashSet<>();
        Arrays.stream(configured.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .forEach(values::add);
        return values;
    }

    private long effectiveDirectLimit() {
        long total = Math.max(1, maximumFileSize);
        long direct = maximumDirectFileSize <= 0 ? total : maximumDirectFileSize;
        return Math.min(total, direct);
    }

    private long effectiveOfferTtl() {
        return Math.max(15, Math.min(offerTtlSeconds, 3_600));
    }

    private long effectiveRelayTtl() {
        return Math.max(60, Math.min(relayTtlSeconds, 86_400));
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " ID 无效");
    }

    private FileTransferVO toView(FileTransfer transfer) {
        Objects.requireNonNull(transfer, "transfer");
        return new FileTransferVO(
                transfer.getTransferId(),
                transfer.getClientTransferId(),
                transfer.getConversationId(),
                transfer.getSenderUserId(),
                transfer.getSenderDeviceId(),
                transfer.getReceiverUserId(),
                transfer.getReceiverDeviceId(),
                transfer.getFileName(),
                transfer.getFileSize(),
                transfer.getFileType(),
                transfer.getFileHash(),
                transfer.getStatus(),
                transfer.getTransportPath(),
                transfer.getFileMetadataId(),
                transfer.getStoredFileName(),
                transfer.getFallbackReason(),
                transfer.getExpiresAt(),
                transfer.getClaimedTime(),
                transfer.getCompletedTime(),
                transfer.getCreateTime(),
                transfer.getUpdateTime()
        );
    }
}
