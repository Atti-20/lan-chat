package com.lanchat.service;

import com.lanchat.dto.FileTransferCompletionDTO;
import com.lanchat.dto.FileTransferOfferDTO;
import com.lanchat.dto.FileTransferRelayCompletionDTO;
import com.lanchat.dto.FileTransferRoute;
import com.lanchat.dto.FileTransferVO;

public interface FileTransferService {

    /** Creates an idempotent private-chat offer and derives the receiver from conversationId. */
    FileTransferVO createOffer(Long senderUserId,
                               Long senderDeviceId,
                               FileTransferOfferDTO request);

    /** Returns transfer metadata only to the originating sender device or an eligible receiver device. */
    FileTransferVO getForParticipant(String transferId, Long userId, Long deviceId);

    /** Atomically lets the first receiver device claim an offer. */
    FileTransferVO claimReceiverDevice(String transferId,
                                       Long receiverUserId,
                                       Long receiverDeviceId);

    /** Resolves the one authorized peer device for SDP/ICE forwarding. */
    FileTransferRoute authorizePeerSignal(String transferId,
                                          Long actorUserId,
                                          Long actorDeviceId);

    /** Records that the sender has started WebRTC negotiation. */
    FileTransferVO markNegotiating(String transferId,
                                   Long senderUserId,
                                   Long senderDeviceId);

    /** Records an open DataChannel/active byte transfer from either claimed participant. */
    FileTransferVO markTransferring(String transferId,
                                    Long actorUserId,
                                    Long actorDeviceId);

    /** Only the claimed receiver device may confirm P2P completion and matching bytes. */
    FileTransferVO completePeerToPeer(String transferId,
                                      Long receiverUserId,
                                      Long receiverDeviceId,
                                      FileTransferCompletionDTO completion);

    /** Selects the existing node upload path after P2P cannot be used. */
    FileTransferVO fallbackToNodeRelay(String transferId,
                                       Long actorUserId,
                                       Long actorDeviceId,
                                       String reason);

    /** Binds a completed, content-inspected node upload to the transfer. */
    FileTransferVO completeNodeRelay(String transferId,
                                     Long senderUserId,
                                     Long senderDeviceId,
                                     FileTransferRelayCompletionDTO completion);

    /** Strict check used before a CHAT_SEND attachment may reference this transfer. */
    FileTransferVO requireCompletedAttachment(String transferId,
                                              String conversationId,
                                              Long senderUserId,
                                              Long senderDeviceId);

    /** Marks all unfinished records past their deadline as EXPIRED. */
    int expirePendingTransfers();
}
