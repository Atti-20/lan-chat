package com.lanchat.service;

import com.lanchat.dto.TemporaryRoomCreateDTO;
import com.lanchat.dto.TemporaryRoomVO;

import java.time.LocalDateTime;
import java.util.List;

public interface TemporaryRoomService {

    TemporaryRoomVO createRoom(Long ownerId, TemporaryRoomCreateDTO dto);

    TemporaryRoomVO joinByCode(Long userId, String roomCode);

    List<TemporaryRoomVO> getMyRooms(Long userId);

    TemporaryRoomVO getRoom(Long roomId, Long userId);

    void leaveRoom(Long roomId, Long userId);

    /** 执行所有在 now 时刻已经到期的 ACTIVE 房间，返回成功取得状态转换权的数量。 */
    int processExpiredRooms(LocalDateTime now);
}
