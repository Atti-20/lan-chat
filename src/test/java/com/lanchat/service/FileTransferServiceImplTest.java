package com.lanchat.service;

import com.lanchat.dto.FileTransferCompletionDTO;
import com.lanchat.dto.FileTransferOfferDTO;
import com.lanchat.dto.FileTransferRelayCompletionDTO;
import com.lanchat.entity.FileTransfer;
import com.lanchat.entity.FileTransferPath;
import com.lanchat.entity.FileTransferStatus;
import com.lanchat.mapper.FileTransferMapper;
import com.lanchat.service.impl.FileTransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferServiceImplTest {

    private static final String TRANSFER_ID = "0123456789abcdef0123456789abcdef";
    private static final String STORED_FILE = "fedcba9876543210fedcba9876543210.pdf";
    private static final String HASH = "a".repeat(64);

    private FileTransferMapper mapper;
    private FileTransferServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(FileTransferMapper.class);
        service = new FileTransferServiceImpl(mapper);
        ReflectionTestUtils.setField(service, "maximumFileSize", 10_000L);
        ReflectionTestUtils.setField(service, "maximumDirectFileSize", 8_000L);
        ReflectionTestUtils.setField(service, "offerTtlSeconds", 120L);
        ReflectionTestUtils.setField(service, "relayTtlSeconds", 3_600L);
        ReflectionTestUtils.setField(service, "allowedTypes", "pdf,png,txt");
    }

    @Test
    void createsIdempotentOfferAndDerivesReceiverFromPrivateConversation() {
        when(mapper.insert(any(FileTransfer.class))).thenReturn(1);
        LocalDateTime before = LocalDateTime.now();

        var result = service.createOffer(7L, 70L, offer("private:7:9", 2_048L));

        ArgumentCaptor<FileTransfer> captor = ArgumentCaptor.forClass(FileTransfer.class);
        verify(mapper).insert(captor.capture());
        FileTransfer inserted = captor.getValue();
        assertTrue(inserted.getTransferId().matches("[0-9a-f]{32}"));
        assertEquals(9L, inserted.getReceiverUserId());
        assertNull(inserted.getReceiverDeviceId());
        assertEquals(FileTransferStatus.OFFERED.name(), inserted.getStatus());
        assertEquals(FileTransferPath.PENDING.name(), inserted.getTransportPath());
        assertTrue(inserted.getExpiresAt().isAfter(before.plusSeconds(100)));
        assertEquals(inserted.getTransferId(), result.transferId());
        assertEquals("application/pdf", result.fileType());
    }

    @Test
    void rejectsSenderThatDoesNotBelongToClaimedConversation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.createOffer(8L, 80L, offer("private:7:9", 2_048L)));

        assertEquals("发送用户不属于目标会话", failure.getMessage());
        verify(mapper, never()).insert(any(FileTransfer.class));
    }

    @Test
    void retryWithSameClientIdReturnsExistingTaskWithoutAnotherInsert() {
        FileTransfer existing = transfer(FileTransferStatus.OFFERED, FileTransferPath.PENDING, null);
        when(mapper.selectBySenderAndClientTransferId(7L, "client_transfer_01"))
                .thenReturn(existing);

        var result = service.createOffer(7L, 70L, offer("private:7:9", 2_048L));

        assertEquals(TRANSFER_ID, result.transferId());
        verify(mapper, never()).insert(any(FileTransfer.class));
    }

    @Test
    void fileAboveDirectLimitStartsInNodeRelayFallback() {
        when(mapper.insert(any(FileTransfer.class))).thenReturn(1);

        var result = service.createOffer(7L, 70L, offer("private:7:9", 9_000L));

        assertEquals(FileTransferStatus.RELAY_PENDING.name(), result.status());
        assertEquals(FileTransferPath.NODE_RELAY.name(), result.transportPath());
        assertEquals("DIRECT_SIZE_LIMIT", result.fallbackReason());
    }

    @Test
    void firstReceiverDeviceClaimsOfferAtomically() {
        FileTransfer offered = transfer(FileTransferStatus.OFFERED, FileTransferPath.PENDING, null);
        FileTransfer claimed = transfer(FileTransferStatus.CLAIMED, FileTransferPath.PENDING, 91L);
        when(mapper.selectByTransferId(TRANSFER_ID)).thenReturn(offered, claimed);
        when(mapper.claimReceiverDevice(
                org.mockito.ArgumentMatchers.eq(TRANSFER_ID),
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(91L),
                any(LocalDateTime.class))).thenReturn(1);

        var result = service.claimReceiverDevice(TRANSFER_ID, 9L, 91L);

        assertEquals(91L, result.receiverDeviceId());
        assertEquals(FileTransferStatus.CLAIMED.name(), result.status());
    }

    @Test
    void losingReceiverDeviceCannotTakeOverConcurrentClaim() {
        FileTransfer offered = transfer(FileTransferStatus.OFFERED, FileTransferPath.PENDING, null);
        FileTransfer wonByOtherDevice = transfer(FileTransferStatus.CLAIMED, FileTransferPath.PENDING, 92L);
        when(mapper.selectByTransferId(TRANSFER_ID)).thenReturn(offered, wonByOtherDevice);
        when(mapper.claimReceiverDevice(
                org.mockito.ArgumentMatchers.eq(TRANSFER_ID),
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(91L),
                any(LocalDateTime.class))).thenReturn(0);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.claimReceiverDevice(TRANSFER_ID, 9L, 91L));

        assertEquals("文件传输已由其他设备接收", failure.getMessage());
    }

    @Test
    void signalingRouteTargetsOnlyTheClaimedPeerDevice() {
        FileTransfer claimed = transfer(FileTransferStatus.CLAIMED, FileTransferPath.PENDING, 91L);
        when(mapper.selectByTransferId(TRANSFER_ID)).thenReturn(claimed);

        var senderRoute = service.authorizePeerSignal(TRANSFER_ID, 7L, 70L);
        assertEquals(9L, senderRoute.targetUserId());
        assertEquals(91L, senderRoute.targetDeviceId());

        assertThrows(IllegalArgumentException.class,
                () -> service.authorizePeerSignal(TRANSFER_ID, 9L, 92L));
    }

    @Test
    void claimedReceiverCompletesP2POnlyAfterHashAndSizeMatch() {
        FileTransfer transferring = transfer(FileTransferStatus.TRANSFERRING, FileTransferPath.PENDING, 91L);
        FileTransfer completed = transfer(FileTransferStatus.P2P_COMPLETED, FileTransferPath.PEER_TO_PEER, 91L);
        completed.setCompletedTime(LocalDateTime.now());
        when(mapper.selectByTransferId(TRANSFER_ID)).thenReturn(transferring, completed);
        when(mapper.completePeerToPeer(
                org.mockito.ArgumentMatchers.eq(TRANSFER_ID),
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(91L),
                org.mockito.ArgumentMatchers.eq(HASH),
                org.mockito.ArgumentMatchers.eq(2_048L),
                any(LocalDateTime.class))).thenReturn(1);

        var result = service.completePeerToPeer(
                TRANSFER_ID, 9L, 91L, new FileTransferCompletionDTO(HASH, 2_048L));

        assertEquals(FileTransferStatus.P2P_COMPLETED.name(), result.status());
        assertEquals(FileTransferPath.PEER_TO_PEER.name(), result.transportPath());
        assertNotNull(result.completedTime());
    }

    @Test
    void hashMismatchCannotCompleteP2P() {
        FileTransfer transferring = transfer(FileTransferStatus.TRANSFERRING, FileTransferPath.PENDING, 91L);
        when(mapper.selectByTransferId(TRANSFER_ID)).thenReturn(transferring);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.completePeerToPeer(TRANSFER_ID, 9L, 91L,
                        new FileTransferCompletionDTO("b".repeat(64), 2_048L)));

        assertEquals("接收文件完整性校验失败", failure.getMessage());
        verify(mapper, never()).completePeerToPeer(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void senderCanDowngradeActiveTransferAndBindInspectedRelayFile() {
        FileTransfer negotiating = transfer(FileTransferStatus.NEGOTIATING, FileTransferPath.PENDING, 91L);
        FileTransfer relayPending = transfer(FileTransferStatus.RELAY_PENDING, FileTransferPath.NODE_RELAY, 91L);
        relayPending.setFallbackReason("RTC_TIMEOUT");
        FileTransfer relayCompleted = transfer(FileTransferStatus.RELAY_COMPLETED, FileTransferPath.NODE_RELAY, 91L);
        relayCompleted.setFallbackReason("RTC_TIMEOUT");
        relayCompleted.setFileMetadataId(55L);
        relayCompleted.setStoredFileName(STORED_FILE);
        relayCompleted.setCompletedTime(LocalDateTime.now());
        when(mapper.selectByTransferId(TRANSFER_ID))
                .thenReturn(negotiating, relayPending, relayPending, relayCompleted);
        when(mapper.fallbackToNodeRelay(
                org.mockito.ArgumentMatchers.eq(TRANSFER_ID),
                org.mockito.ArgumentMatchers.eq("RTC_TIMEOUT"),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(mapper.completeNodeRelay(
                org.mockito.ArgumentMatchers.eq(TRANSFER_ID),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(70L),
                org.mockito.ArgumentMatchers.eq(55L),
                org.mockito.ArgumentMatchers.eq(STORED_FILE),
                org.mockito.ArgumentMatchers.eq(HASH),
                org.mockito.ArgumentMatchers.eq(2_048L),
                any(LocalDateTime.class))).thenReturn(1);

        var pending = service.fallbackToNodeRelay(TRANSFER_ID, 7L, 70L, "rtc_timeout");
        var completed = service.completeNodeRelay(TRANSFER_ID, 7L, 70L,
                new FileTransferRelayCompletionDTO(55L, STORED_FILE, HASH, 2_048L));

        assertEquals(FileTransferStatus.RELAY_PENDING.name(), pending.status());
        assertEquals(FileTransferStatus.RELAY_COMPLETED.name(), completed.status());
        assertEquals(55L, completed.fileMetadataId());
        assertEquals(STORED_FILE, completed.storedFileName());
    }

    @Test
    void chatAttachmentValidationBindsConversationSenderAndOriginatingDevice() {
        FileTransfer completed = transfer(FileTransferStatus.P2P_COMPLETED, FileTransferPath.PEER_TO_PEER, 91L);
        when(mapper.selectByTransferId(TRANSFER_ID)).thenReturn(completed);

        var result = service.requireCompletedAttachment(TRANSFER_ID, "private:7:9", 7L, 70L);
        assertEquals(TRANSFER_ID, result.transferId());

        assertThrows(IllegalArgumentException.class,
                () -> service.requireCompletedAttachment(TRANSFER_ID, "private:7:9", 7L, 71L));
    }

    @Test
    void expiredOfferIsPersistentlyMarkedBeforeItCanBeClaimed() {
        FileTransfer expired = transfer(FileTransferStatus.OFFERED, FileTransferPath.PENDING, null);
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectByTransferId(TRANSFER_ID)).thenReturn(expired);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.claimReceiverDevice(TRANSFER_ID, 9L, 91L));

        assertEquals("文件传输已经过期", failure.getMessage());
        verify(mapper).expireOne(org.mockito.ArgumentMatchers.eq(TRANSFER_ID), any(LocalDateTime.class));
    }

    private FileTransferOfferDTO offer(String conversationId, long size) {
        return new FileTransferOfferDTO(
                "client_transfer_01",
                conversationId,
                "report.pdf",
                size,
                "application/pdf",
                HASH
        );
    }

    private FileTransfer transfer(FileTransferStatus status,
                                  FileTransferPath path,
                                  Long receiverDeviceId) {
        FileTransfer transfer = new FileTransfer();
        transfer.setId(1L);
        transfer.setTransferId(TRANSFER_ID);
        transfer.setClientTransferId("client_transfer_01");
        transfer.setConversationId("private:7:9");
        transfer.setSenderUserId(7L);
        transfer.setSenderDeviceId(70L);
        transfer.setReceiverUserId(9L);
        transfer.setReceiverDeviceId(receiverDeviceId);
        transfer.setFileName("report.pdf");
        transfer.setFileSize(2_048L);
        transfer.setFileType("application/pdf");
        transfer.setFileHash(HASH);
        transfer.setStatus(status.name());
        transfer.setTransportPath(path.name());
        transfer.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        transfer.setCreateTime(LocalDateTime.now().minusSeconds(1));
        transfer.setUpdateTime(LocalDateTime.now());
        if (receiverDeviceId != null) transfer.setClaimedTime(LocalDateTime.now().minusSeconds(1));
        return transfer;
    }
}
