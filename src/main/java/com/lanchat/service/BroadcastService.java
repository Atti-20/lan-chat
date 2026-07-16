package com.lanchat.service;

import com.lanchat.dto.BroadcastConfirmDTO;
import com.lanchat.dto.BroadcastCreateDTO;
import com.lanchat.dto.BroadcastDetailDTO;
import com.lanchat.dto.BroadcastStatsDTO;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;

import java.util.List;

public interface BroadcastService {

    Broadcast create(Long senderId, BroadcastCreateDTO request);

    /** 撤销广播并保留广播正文、接收快照与回执历史。 */
    Broadcast cancel(Long broadcastId, Long operatorId);

    List<Broadcast> listVisible(Long userId);

    List<Broadcast> listPending(Long userId);

    BroadcastDetailDTO getDetail(Long broadcastId, Long userId);

    /** Internal dispatch target snapshot for WebSocket integration. */
    List<Long> getReceiverIds(Long broadcastId);

    BroadcastReceiver markDelivered(Long broadcastId, Long userId);

    BroadcastReceiver markViewed(Long broadcastId, Long userId);

    BroadcastReceiver confirm(Long broadcastId,
                              Long userId,
                              String deviceType,
                              BroadcastConfirmDTO request);

    BroadcastStatsDTO getStats(Long broadcastId, Long userId);
}
