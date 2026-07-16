package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanchat.common.ConversationIds;
import com.lanchat.entity.ConversationMember;
import com.lanchat.entity.Conversation;
import com.lanchat.entity.GroupMember;
import com.lanchat.entity.TemporaryRoom;
import com.lanchat.mapper.ConversationMapper;
import com.lanchat.mapper.ConversationMemberMapper;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.GroupMemberMapper;
import com.lanchat.mapper.TemporaryRoomMapper;
import com.lanchat.service.ConversationService;
import com.lanchat.service.FriendService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@Service
public class ConversationServiceImpl implements ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper conversationMemberMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final TemporaryRoomMapper temporaryRoomMapper;
    private final FriendService friendService;

    public ConversationServiceImpl(ConversationMapper conversationMapper,
                                   ConversationMemberMapper conversationMemberMapper,
                                   ChatMessageMapper chatMessageMapper,
                                   GroupMemberMapper groupMemberMapper,
                                   TemporaryRoomMapper temporaryRoomMapper,
                                   FriendService friendService) {
        this.conversationMapper = conversationMapper;
        this.conversationMemberMapper = conversationMemberMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.temporaryRoomMapper = temporaryRoomMapper;
        this.friendService = friendService;
    }

    @Override
    @Transactional
    public String ensurePrivateConversation(Long firstUserId, Long secondUserId) {
        String conversationId = ConversationIds.privateConversation(firstUserId, secondUserId);
        conversationMapper.insertIfAbsent(conversationId, "PRIVATE", null);
        conversationMemberMapper.insertIfAbsent(conversationId, firstUserId, "MEMBER");
        conversationMemberMapper.insertIfAbsent(conversationId, secondUserId, "MEMBER");
        return conversationId;
    }

    @Override
    @Transactional
    public String ensureGroupConversation(Long groupId) {
        String conversationId = ConversationIds.groupConversation(groupId);
        conversationMapper.insertIfAbsent(conversationId, "GROUP", groupId);

        List<GroupMember> members = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId));
        for (GroupMember member : members) {
            conversationMemberMapper.insertIfAbsent(
                    conversationId,
                    member.getUserId(),
                    groupRole(member.getRole())
            );
        }
        return conversationId;
    }

    @Override
    public String ensureTemporaryConversation(Long roomId) {
        String conversationId = ConversationIds.temporaryConversation(roomId);
        conversationMapper.insertIfAbsent(conversationId, "TEMPORARY", roomId);
        return conversationId;
    }

    @Override
    public void addConversationMember(String conversationId, Long userId, String role) {
        if (!StringUtils.hasText(conversationId) || userId == null) {
            throw new IllegalArgumentException("会话成员参数无效");
        }
        String safeRole = switch (role == null ? "MEMBER" : role.trim().toUpperCase()) {
            case "OWNER" -> "OWNER";
            case "ADMIN" -> "ADMIN";
            case "READ_ONLY" -> "READ_ONLY";
            default -> "MEMBER";
        };
        conversationMemberMapper.insertIfAbsent(conversationId, userId, safeRole);
    }

    @Override
    public void removeConversationMember(String conversationId, Long userId) {
        if (StringUtils.hasText(conversationId) && userId != null) {
            conversationMemberMapper.markLeft(conversationId, userId);
        }
    }

    @Override
    public void removeAllConversationMembers(String conversationId) {
        if (StringUtils.hasText(conversationId)) conversationMemberMapper.markAllLeft(conversationId);
    }

    @Override
    public void updateStatus(String conversationId, String status) {
        if (!StringUtils.hasText(conversationId)) throw new IllegalArgumentException("会话标识无效");
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!List.of("ACTIVE", "READ_ONLY", "ARCHIVED", "DESTROYED").contains(normalized)) {
            throw new IllegalArgumentException("会话状态无效");
        }
        conversationMapper.updateStatus(conversationId, normalized);
    }

    @Override
    public List<Long> getActiveMemberIds(String conversationId) {
        if (!StringUtils.hasText(conversationId)) return List.of();
        return conversationMemberMapper.selectList(new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .isNull(ConversationMember::getLeftTime)
                        .orderByAsc(ConversationMember::getId))
                .stream()
                .map(ConversationMember::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public List<String> getAccessibleConversationIds(Long userId) {
        if (userId == null) return List.of();
        List<ConversationMember> memberships = conversationMemberMapper.selectList(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getUserId, userId)
                        .isNull(ConversationMember::getLeftTime));
        List<String> result = new ArrayList<>();
        for (ConversationMember membership : memberships) {
            if (canAccess(membership.getConversationId(), userId)) result.add(membership.getConversationId());
        }
        return result.stream().distinct().toList();
    }

    @Override
    public String resolveForMessage(Long senderId,
                                    Long toUserId,
                                    Long groupId,
                                    String requestedConversationId) {
        boolean hasPrivateTarget = toUserId != null;
        boolean hasGroupTarget = groupId != null;
        if (!hasPrivateTarget && !hasGroupTarget
                && ConversationIds.parseTemporary(requestedConversationId).isPresent()) {
            String resolved = ConversationIds.temporaryConversation(
                    ConversationIds.parseTemporary(requestedConversationId).orElseThrow());
            if (!canSend(resolved, senderId)) {
                throw new IllegalArgumentException("当前用户无权在该临时房间发送消息");
            }
            return resolved;
        }
        if (hasPrivateTarget == hasGroupTarget) {
            throw new IllegalArgumentException("消息接收方无效");
        }

        String resolved;
        if (hasGroupTarget) {
            // 群成员全量同步由建群/加人/移除等成员变更触发；发送热路径只确保会话壳存在，
            // 避免每条群消息都遍历并 upsert 全部成员。
            resolved = ConversationIds.groupConversation(groupId);
            conversationMapper.insertIfAbsent(resolved, "GROUP", groupId);
        } else {
            resolved = ensurePrivateConversation(senderId, toUserId);
        }
        if (StringUtils.hasText(requestedConversationId)
                && !resolved.equals(requestedConversationId.trim())) {
            throw new IllegalArgumentException("会话标识与消息目标不一致");
        }
        if (!canSend(resolved, senderId)) {
            throw new IllegalArgumentException("当前用户无权在该会话发送消息");
        }
        return resolved;
    }

    @Override
    public boolean canAccess(String conversationId, Long userId) {
        if (!StringUtils.hasText(conversationId) || userId == null) return false;

        var privateParticipants = ConversationIds.parsePrivate(conversationId);
        if (privateParticipants.isPresent()) {
            return privateParticipants.get().contains(userId);
        }

        var groupId = ConversationIds.parseGroup(conversationId);
        if (groupId.isPresent()) {
            return groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, groupId.get())
                    .eq(GroupMember::getUserId, userId)) > 0;
        }

        Long roomId = ConversationIds.parseTemporary(conversationId).orElse(null);
        if (roomId == null) return false;
        TemporaryRoom room = temporaryRoomMapper.selectById(roomId);
        if (room == null || "DESTROYED".equals(room.getStatus())) return false;
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || "DESTROYED".equals(conversation.getStatus())) return false;
        return conversationMemberMapper.selectCount(new LambdaQueryWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId)
                .isNull(ConversationMember::getLeftTime)) > 0;
    }

    @Override
    public boolean canSend(String conversationId, Long userId) {
        if (!canAccess(conversationId, userId)) return false;
        Long temporaryRoomId = ConversationIds.parseTemporary(conversationId).orElse(null);
        if (temporaryRoomId != null) {
            TemporaryRoom room = temporaryRoomMapper.selectById(temporaryRoomId);
            if (room == null || !"ACTIVE".equals(room.getStatus())
                    || room.getExpiresAt() == null
                    || !room.getExpiresAt().isAfter(LocalDateTime.now())) return false;
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation != null && !"ACTIVE".equals(conversation.getStatus())) return false;
        var privateParticipants = ConversationIds.parsePrivate(conversationId);
        if (privateParticipants.isPresent()) {
            long peerId = privateParticipants.get().peerOf(userId);
            return friendService.isFriend(userId, peerId)
                    && !friendService.isBlockedBy(userId, peerId);
        }

        Long groupId = ConversationIds.parseGroup(conversationId).orElse(null);
        if (groupId != null) {
            GroupMember membership = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, groupId)
                    .eq(GroupMember::getUserId, userId)
                    .last("LIMIT 1"));
            return membership != null
                    && (membership.getMuteUntil() == null
                    || membership.getMuteUntil().isBefore(LocalDateTime.now()));
        }

        ConversationMember membership = conversationMemberMapper.selectOne(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .eq(ConversationMember::getUserId, userId)
                        .isNull(ConversationMember::getLeftTime)
                        .last("LIMIT 1"));
        return membership != null && !"READ_ONLY".equals(membership.getRole());
    }

    @Override
    public boolean canUploadFile(String conversationId, Long userId) {
        if (!canSend(conversationId, userId)) return false;
        Long roomId = ConversationIds.parseTemporary(conversationId).orElse(null);
        if (roomId == null) return true;
        TemporaryRoom room = temporaryRoomMapper.selectById(roomId);
        return room != null && Integer.valueOf(1).equals(room.getAllowFileUpload());
    }

    @Override
    public boolean canDownloadFile(String conversationId, Long userId) {
        if (!canAccess(conversationId, userId)) return false;
        Long roomId = ConversationIds.parseTemporary(conversationId).orElse(null);
        if (roomId == null) return true;
        TemporaryRoom room = temporaryRoomMapper.selectById(roomId);
        return room != null
                && !"DESTROYED".equals(room.getStatus())
                && Integer.valueOf(1).equals(room.getAllowFileDownload());
    }

    @Override
    public long nextSequence(String conversationId) {
        if (conversationMapper.incrementSequence(conversationId) != 1) {
            throw new IllegalStateException("会话不存在或当前不可发送");
        }
        Long sequence = conversationMapper.selectLastSequence(conversationId);
        if (sequence == null || sequence <= 0) {
            throw new IllegalStateException("会话序列号分配失败");
        }
        return sequence;
    }

    @Override
    public long getLastSequence(String conversationId) {
        Long sequence = conversationMapper.selectLastSequence(conversationId);
        return sequence == null ? 0 : sequence;
    }

    @Override
    public void updateLastMessage(String conversationId, String messageId, Long senderId) {
        conversationMapper.updateLastMessage(conversationId, messageId);
        conversationMemberMapper.incrementUnread(conversationId, senderId);
    }

    @Override
    @Transactional
    public void markRead(String conversationId, Long userId, long sequence) {
        if (!canAccess(conversationId, userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        long safeSequence = Math.max(0, Math.min(sequence, getLastSequence(conversationId)));
        if (ConversationIds.parsePrivate(conversationId).isPresent()) {
            var participants = ConversationIds.parsePrivate(conversationId).orElseThrow();
            ensurePrivateConversation(participants.firstUserId(), participants.secondUserId());
        } else if (ConversationIds.parseGroup(conversationId).isPresent()) {
            ensureGroupConversation(ConversationIds.parseGroup(conversationId).orElseThrow());
        }
        conversationMemberMapper.advanceReadSequence(conversationId, userId, safeSequence);
        if (ConversationIds.parsePrivate(conversationId).isPresent()) {
            chatMessageMapper.markPrivateMessagesRead(conversationId, userId, safeSequence);
        }
    }

    @Override
    public Map<String, Long> getReadPositions(Long userId) {
        if (userId == null) return Map.of();
        List<ConversationMember> memberships = conversationMemberMapper.selectList(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getUserId, userId)
                        .isNull(ConversationMember::getLeftTime)
                        .orderByAsc(ConversationMember::getConversationId)
        );
        Map<String, Long> positions = new LinkedHashMap<>();
        for (ConversationMember membership : memberships) {
            if (canAccess(membership.getConversationId(), userId)) {
                positions.put(membership.getConversationId(),
                        membership.getLastReadSequence() == null ? 0 : membership.getLastReadSequence());
            }
        }
        return positions;
    }

    @Override
    public void markGroupMemberLeft(Long groupId, Long userId) {
        conversationMemberMapper.markLeft(ConversationIds.groupConversation(groupId), userId);
    }

    @Override
    @Transactional
    public void archiveGroupConversation(Long groupId) {
        String conversationId = ConversationIds.groupConversation(groupId);
        conversationMapper.updateStatus(conversationId, "DESTROYED");
        conversationMemberMapper.markAllLeft(conversationId);
    }

    private String groupRole(Integer role) {
        if (Integer.valueOf(2).equals(role)) return "OWNER";
        if (Integer.valueOf(1).equals(role)) return "ADMIN";
        return "MEMBER";
    }
}
