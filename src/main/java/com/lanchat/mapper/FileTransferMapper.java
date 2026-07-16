package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.FileTransfer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface FileTransferMapper extends BaseMapper<FileTransfer> {

    @Select("""
            SELECT id, transfer_id, client_transfer_id, conversation_id,
                   sender_user_id, sender_device_id, receiver_user_id, receiver_device_id,
                   file_name, file_size, file_type, file_hash, status, transport_path,
                   file_metadata_id, stored_file_name, fallback_reason, expires_at,
                   claimed_time, completed_time, create_time, update_time
            FROM file_transfer
            WHERE transfer_id = #{transferId}
            LIMIT 1
            """)
    FileTransfer selectByTransferId(@Param("transferId") String transferId);

    @Select("""
            SELECT id, transfer_id, client_transfer_id, conversation_id,
                   sender_user_id, sender_device_id, receiver_user_id, receiver_device_id,
                   file_name, file_size, file_type, file_hash, status, transport_path,
                   file_metadata_id, stored_file_name, fallback_reason, expires_at,
                   claimed_time, completed_time, create_time, update_time
            FROM file_transfer
            WHERE sender_user_id = #{senderUserId}
              AND client_transfer_id = #{clientTransferId}
            LIMIT 1
            """)
    FileTransfer selectBySenderAndClientTransferId(@Param("senderUserId") Long senderUserId,
                                                   @Param("clientTransferId") String clientTransferId);

    @Update("""
            UPDATE file_transfer
            SET receiver_device_id = #{receiverDeviceId},
                status = 'CLAIMED',
                claimed_time = #{now},
                update_time = #{now}
            WHERE transfer_id = #{transferId}
              AND receiver_user_id = #{receiverUserId}
              AND receiver_device_id IS NULL
              AND status = 'OFFERED'
              AND expires_at > #{now}
            """)
    int claimReceiverDevice(@Param("transferId") String transferId,
                            @Param("receiverUserId") Long receiverUserId,
                            @Param("receiverDeviceId") Long receiverDeviceId,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE file_transfer
            SET status = 'NEGOTIATING', update_time = #{now}
            WHERE transfer_id = #{transferId}
              AND sender_user_id = #{senderUserId}
              AND sender_device_id = #{senderDeviceId}
              AND status = 'CLAIMED'
              AND expires_at > #{now}
            """)
    int markNegotiating(@Param("transferId") String transferId,
                        @Param("senderUserId") Long senderUserId,
                        @Param("senderDeviceId") Long senderDeviceId,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE file_transfer
            SET status = 'TRANSFERRING', update_time = #{now}
            WHERE transfer_id = #{transferId}
              AND status IN ('CLAIMED', 'NEGOTIATING')
              AND expires_at > #{now}
            """)
    int markTransferring(@Param("transferId") String transferId,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE file_transfer
            SET status = 'P2P_COMPLETED',
                transport_path = 'PEER_TO_PEER',
                completed_time = #{now},
                update_time = #{now}
            WHERE transfer_id = #{transferId}
              AND receiver_user_id = #{receiverUserId}
              AND receiver_device_id = #{receiverDeviceId}
              AND file_hash = #{fileHash}
              AND file_size = #{fileSize}
              AND status IN ('CLAIMED', 'NEGOTIATING', 'TRANSFERRING')
              AND expires_at > #{now}
            """)
    int completePeerToPeer(@Param("transferId") String transferId,
                           @Param("receiverUserId") Long receiverUserId,
                           @Param("receiverDeviceId") Long receiverDeviceId,
                           @Param("fileHash") String fileHash,
                           @Param("fileSize") Long fileSize,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE file_transfer
            SET status = 'RELAY_PENDING',
                transport_path = 'NODE_RELAY',
                fallback_reason = #{reason},
                expires_at = #{relayExpiresAt},
                update_time = #{now}
            WHERE transfer_id = #{transferId}
              AND status IN ('OFFERED', 'CLAIMED', 'NEGOTIATING', 'TRANSFERRING')
              AND expires_at > #{now}
            """)
    int fallbackToNodeRelay(@Param("transferId") String transferId,
                            @Param("reason") String reason,
                            @Param("relayExpiresAt") LocalDateTime relayExpiresAt,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE file_transfer
            SET status = 'RELAY_COMPLETED',
                transport_path = 'NODE_RELAY',
                file_metadata_id = #{fileMetadataId},
                stored_file_name = #{storedFileName},
                completed_time = #{now},
                update_time = #{now}
            WHERE transfer_id = #{transferId}
              AND sender_user_id = #{senderUserId}
              AND sender_device_id = #{senderDeviceId}
              AND file_hash = #{fileHash}
              AND file_size = #{fileSize}
              AND status = 'RELAY_PENDING'
              AND expires_at > #{now}
            """)
    int completeNodeRelay(@Param("transferId") String transferId,
                          @Param("senderUserId") Long senderUserId,
                          @Param("senderDeviceId") Long senderDeviceId,
                          @Param("fileMetadataId") Long fileMetadataId,
                          @Param("storedFileName") String storedFileName,
                          @Param("fileHash") String fileHash,
                          @Param("fileSize") Long fileSize,
                          @Param("now") LocalDateTime now);

    @Update("""
            UPDATE file_transfer
            SET status = 'EXPIRED', update_time = #{now}
            WHERE transfer_id = #{transferId}
              AND status IN ('OFFERED', 'CLAIMED', 'NEGOTIATING', 'TRANSFERRING', 'RELAY_PENDING')
              AND expires_at <= #{now}
            """)
    int expireOne(@Param("transferId") String transferId,
                  @Param("now") LocalDateTime now);

    @Update("""
            UPDATE file_transfer
            SET status = 'EXPIRED', update_time = #{now}
            WHERE status IN ('OFFERED', 'CLAIMED', 'NEGOTIATING', 'TRANSFERRING', 'RELAY_PENDING')
              AND expires_at <= #{now}
            """)
    int expirePending(@Param("now") LocalDateTime now);
}
