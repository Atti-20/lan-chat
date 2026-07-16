package com.lanchat.service.impl;

import com.lanchat.common.ConversationIds;
import com.lanchat.common.TemporaryRoomChangedEvent;
import com.lanchat.dto.TemporaryRoomCreateDTO;
import com.lanchat.dto.TemporaryRoomVO;
import com.lanchat.entity.TemporaryRoom;
import com.lanchat.mapper.TemporaryRoomMapper;
import com.lanchat.service.ConversationService;
import com.lanchat.service.TemporaryRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class TemporaryRoomServiceImpl implements TemporaryRoomService {

    private static final Logger log = LoggerFactory.getLogger(TemporaryRoomServiceImpl.class);
    private static final Set<String> EXPIRE_ACTIONS = Set.of("FREEZE", "ARCHIVE", "DESTROY");
    private static final int DEFAULT_MAX_MEMBERS = 50;
    private static final int MAX_MEMBERS = 200;
    private static final int DEFAULT_RETENTION_DAYS = 7;

    private final TemporaryRoomMapper roomMapper;
    private final ConversationService conversationService;
    private final ApplicationEventPublisher eventPublisher;

    public TemporaryRoomServiceImpl(TemporaryRoomMapper roomMapper,
                                    ConversationService conversationService,
                                    ApplicationEventPublisher eventPublisher) {
        this.roomMapper = roomMapper;
        this.conversationService = conversationService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public TemporaryRoomVO createRoom(Long ownerId, TemporaryRoomCreateDTO dto) {
        if (ownerId == null || ownerId <= 0 || dto == null) {
            throw new IllegalArgumentException("临时房间参数不完整");
        }

        LocalDateTime now = LocalDateTime.now();
        String roomName = normalizeRequired(dto.getRoomName(), 2, 50, "房间名称长度需为2-50字符");
        String purpose = normalizeOptional(dto.getPurpose(), 500, "房间用途不能超过500个字符");
        if (dto.getExpiresAt() == null || !dto.getExpiresAt().isAfter(now)) {
            throw new IllegalArgumentException("房间到期时间必须晚于当前时间");
        }
        int maxMembers = dto.getMaxMembers() == null ? DEFAULT_MAX_MEMBERS : dto.getMaxMembers();
        if (maxMembers < 2 || maxMembers > MAX_MEMBERS) {
            throw new IllegalArgumentException("房间成员上限需为2-200人");
        }
        int retentionDays = dto.getMessageRetentionDays() == null
                ? DEFAULT_RETENTION_DAYS : dto.getMessageRetentionDays();
        if (retentionDays < 1 || retentionDays > 365) {
            throw new IllegalArgumentException("消息保存期限需为1-365天");
        }
        String expireAction = normalizeExpireAction(dto.getExpireAction());

        TemporaryRoom room = new TemporaryRoom();
        room.setRoomName(roomName);
        room.setPurpose(purpose);
        room.setOwnerId(ownerId);
        room.setRoomCode(generateUniqueRoomCode());
        room.setExpiresAt(dto.getExpiresAt());
        room.setMaxMembers(maxMembers);
        room.setAllowGuests(flag(dto.getAllowGuests(), false));
        room.setAllowMemberInvite(flag(dto.getAllowMemberInvite(), true));
        room.setAllowFileUpload(flag(dto.getAllowFileUpload(), true));
        room.setAllowFileDownload(flag(dto.getAllowFileDownload(), true));
        room.setAllowForward(flag(dto.getAllowForward(), false));
        room.setMessageRetentionDays(retentionDays);
        room.setAllowExternalSync(flag(dto.getAllowExternalSync(), false));
        room.setExpireAction(expireAction);
        room.setStatus("ACTIVE");
        room.setCreateTime(now);
        room.setUpdateTime(now);
        if (roomMapper.insert(room) != 1 || room.getId() == null) {
            throw new IllegalStateException("临时房间创建失败");
        }

        String conversationId = conversationService.ensureTemporaryConversation(room.getId());
        conversationService.addConversationMember(conversationId, ownerId, "OWNER");
        publishChanged(room.getId(), conversationId, "ACTIVE", List.of(ownerId));
        return toView(room, ownerId);
    }

    @Override
    @Transactional
    public TemporaryRoomVO joinByCode(Long userId, String roomCode) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("用户参数无效");
        String normalizedCode = normalizeRoomCode(roomCode);
        TemporaryRoom room = roomMapper.selectByRoomCodeForUpdate(normalizedCode);
        if (room == null) throw new IllegalArgumentException("房间码无效");

        LocalDateTime now = LocalDateTime.now();
        if (!"ACTIVE".equals(room.getStatus()) || !room.getExpiresAt().isAfter(now)) {
            throw new IllegalArgumentException("临时房间已到期或停止加入");
        }

        String currentRole = roomMapper.selectMemberRole(room.getId(), userId);
        if (currentRole != null) return toView(room, userId);
        if (roomMapper.countActiveMembers(room.getId()) >= room.getMaxMembers()) {
            throw new IllegalArgumentException("临时房间成员数量已达上限");
        }

        String conversationId = conversationService.ensureTemporaryConversation(room.getId());
        conversationService.addConversationMember(conversationId, userId, "MEMBER");
        roomMapper.touch(room.getId(), now);
        room.setUpdateTime(now);
        publishChanged(room.getId(), conversationId, "ACTIVE",
                conversationService.getActiveMemberIds(conversationId));
        return toView(room, userId);
    }

    @Override
    public List<TemporaryRoomVO> getMyRooms(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("用户参数无效");
        return roomMapper.selectByMemberId(userId).stream()
                .map(room -> toView(room, userId))
                .toList();
    }

    @Override
    public TemporaryRoomVO getRoom(Long roomId, Long userId) {
        TemporaryRoom room = requireRoom(roomId);
        if (userId == null || roomMapper.selectMemberRole(roomId, userId) == null) {
            throw new IllegalArgumentException("无权访问该临时房间");
        }
        return toView(room, userId);
    }

    @Override
    @Transactional
    public void leaveRoom(Long roomId, Long userId) {
        TemporaryRoom room = roomMapper.selectByIdForUpdate(roomId);
        if (room == null || "DESTROYED".equals(room.getStatus())) {
            throw new IllegalArgumentException("临时房间不存在");
        }
        String role = roomMapper.selectMemberRole(roomId, userId);
        if (role == null) throw new IllegalArgumentException("你不在该临时房间");
        if ("OWNER".equals(role) || userId.equals(room.getOwnerId())) {
            throw new IllegalArgumentException("房间所有者不能直接离开临时房间");
        }
        String conversationId = conversationId(roomId);
        conversationService.removeConversationMember(conversationId, userId);
        roomMapper.touch(roomId, LocalDateTime.now());
        List<Long> affectedUsers = new ArrayList<>(conversationService.getActiveMemberIds(conversationId));
        if (!affectedUsers.contains(userId)) affectedUsers.add(userId);
        publishChanged(roomId, conversationId, room.getStatus(), affectedUsers);
    }

    @Override
    @Transactional
    public int processExpiredRooms(LocalDateTime now) {
        if (now == null) throw new IllegalArgumentException("生命周期处理时间不能为空");
        int processed = 0;
        for (TemporaryRoom room : roomMapper.selectExpiredActiveRooms(now)) {
            String action = normalizePersistedExpireAction(room.getExpireAction());
            String roomStatus = switch (action) {
                case "ARCHIVE" -> "ARCHIVED";
                case "DESTROY" -> "DESTROYED";
                default -> "FROZEN";
            };
            if (roomMapper.transitionExpiredRoom(room.getId(), roomStatus, now) != 1) continue;

            String conversationId = conversationId(room.getId());
            List<Long> affectedUsers = conversationService.getActiveMemberIds(conversationId);
            String conversationStatus = switch (action) {
                case "ARCHIVE" -> "ARCHIVED";
                case "DESTROY" -> "DESTROYED";
                default -> "READ_ONLY";
            };
            if ("DESTROY".equals(action)) {
                conversationService.removeAllConversationMembers(conversationId);
            }
            conversationService.updateStatus(conversationId, conversationStatus);
            publishChanged(room.getId(), conversationId, roomStatus, affectedUsers);
            processed++;
            log.info("Temporary room {} expired with action {} and status {}",
                    room.getId(), action, roomStatus);
        }
        return processed;
    }

    private TemporaryRoom requireRoom(Long roomId) {
        if (roomId == null || roomId <= 0) throw new IllegalArgumentException("房间参数无效");
        TemporaryRoom room = roomMapper.selectById(roomId);
        if (room == null || "DESTROYED".equals(room.getStatus())) {
            throw new IllegalArgumentException("临时房间不存在");
        }
        return room;
    }

    private TemporaryRoomVO toView(TemporaryRoom room, Long userId) {
        String role = roomMapper.selectMemberRole(room.getId(), userId);
        TemporaryRoomVO view = new TemporaryRoomVO();
        view.setId(room.getId());
        view.setConversationId(conversationId(room.getId()));
        view.setRoomName(room.getRoomName());
        view.setPurpose(room.getPurpose());
        view.setOwnerId(room.getOwnerId());
        if ("OWNER".equals(role) || Integer.valueOf(1).equals(room.getAllowMemberInvite())) {
            view.setRoomCode(room.getRoomCode());
        }
        view.setExpiresAt(room.getExpiresAt());
        view.setMaxMembers(room.getMaxMembers());
        view.setMemberCount(roomMapper.countActiveMembers(room.getId()));
        view.setCurrentUserRole(role);
        view.setAllowGuests(enabled(room.getAllowGuests()));
        view.setAllowMemberInvite(enabled(room.getAllowMemberInvite()));
        view.setAllowFileUpload(enabled(room.getAllowFileUpload()));
        view.setAllowFileDownload(enabled(room.getAllowFileDownload()));
        view.setAllowForward(enabled(room.getAllowForward()));
        view.setMessageRetentionDays(room.getMessageRetentionDays());
        view.setAllowExternalSync(enabled(room.getAllowExternalSync()));
        view.setExpireAction(room.getExpireAction());
        view.setStatus(room.getStatus());
        view.setCreateTime(room.getCreateTime());
        view.setUpdateTime(room.getUpdateTime());
        return view;
    }

    private String generateUniqueRoomCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 12).toUpperCase(Locale.ROOT);
            if (roomMapper.countByRoomCode(code) == 0) return code;
        }
        throw new IllegalStateException("暂时无法生成房间码，请稍后重试");
    }

    private String normalizeRoomCode(String roomCode) {
        String value = roomCode == null ? "" : roomCode.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("^[A-F0-9]{12}$")) throw new IllegalArgumentException("房间码无效");
        return value;
    }

    private String normalizeExpireAction(String action) {
        String normalized = StringUtils.hasText(action)
                ? action.trim().toUpperCase(Locale.ROOT) : "FREEZE";
        if (!EXPIRE_ACTIONS.contains(normalized)) {
            throw new IllegalArgumentException("到期处理方式仅支持 FREEZE、ARCHIVE 或 DESTROY");
        }
        return normalized;
    }

    private String normalizePersistedExpireAction(String action) {
        String normalized = StringUtils.hasText(action)
                ? action.trim().toUpperCase(Locale.ROOT) : "FREEZE";
        if (EXPIRE_ACTIONS.contains(normalized)) return normalized;
        log.warn("Temporary room has unsupported expire action {}, falling back to FREEZE", action);
        return "FREEZE";
    }

    private String normalizeRequired(String value, int minimum, int maximum, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < minimum || normalized.length() > maximum) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maximum, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) throw new IllegalArgumentException(message);
        return normalized;
    }

    private int flag(Boolean value, boolean defaultValue) {
        return Boolean.TRUE.equals(value != null ? value : defaultValue) ? 1 : 0;
    }

    private boolean enabled(Integer value) {
        return Integer.valueOf(1).equals(value);
    }

    private String conversationId(Long roomId) {
        return ConversationIds.temporaryConversation(roomId);
    }

    private void publishChanged(Long roomId,
                                String conversationId,
                                String status,
                                List<Long> memberIds) {
        eventPublisher.publishEvent(new TemporaryRoomChangedEvent(
                roomId, conversationId, status, memberIds));
    }
}
