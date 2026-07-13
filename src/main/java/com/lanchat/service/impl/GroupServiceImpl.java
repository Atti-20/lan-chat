package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lanchat.dto.GroupCreateDTO;
import com.lanchat.dto.GroupUpdateDTO;
import com.lanchat.entity.ChatGroup;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.GroupMember;
import com.lanchat.entity.User;
import com.lanchat.mapper.ChatGroupMapper;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.GroupMemberMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class GroupServiceImpl extends ServiceImpl<ChatGroupMapper, ChatGroup> implements GroupService {

    @Autowired
    private ChatGroupMapper groupMapper;

    @Autowired
    private GroupMemberMapper memberMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    /** 最大管理员数量 */
    private static final int MAX_ADMINS = 3;

    @Override
    @Transactional
    public ChatGroup createGroup(Long ownerId, GroupCreateDTO dto) {
        // 创建群组
        // 群名称校验：2-20字符
        if (dto.getGroupName() == null || dto.getGroupName().length() < 2 || dto.getGroupName().length() > 20) {
            throw new IllegalArgumentException("群名称长度需为2-20字符");
        }

        ChatGroup group = new ChatGroup();
        group.setGroupName(dto.getGroupName());
        group.setAvatar(dto.getAvatar() != null ? dto.getAvatar() : "");
        group.setAnnouncement(dto.getAnnouncement() != null ? dto.getAnnouncement() : "");
        group.setOwnerId(ownerId);
        group.setMaxMembers(200);
        group.setJoinMode(0);
        group.setCreateTime(LocalDateTime.now());
        group.setUpdateTime(LocalDateTime.now());
        groupMapper.insert(group);

        // 添加群主为成员
        GroupMember ownerMember = new GroupMember();
        ownerMember.setGroupId(group.getId());
        ownerMember.setUserId(ownerId);
        ownerMember.setRole(2);
        ownerMember.setJoinTime(LocalDateTime.now());
        memberMapper.insert(ownerMember);

        // 添加其他成员
        if (dto.getMemberIds() != null) {
            for (Long userId : dto.getMemberIds()) {
                if (!userId.equals(ownerId) && userMapper.selectById(userId) != null) {
                    GroupMember member = new GroupMember();
                    member.setGroupId(group.getId());
                    member.setUserId(userId);
                    member.setRole(0);
                    member.setJoinTime(LocalDateTime.now());
                    memberMapper.insert(member);
                }
            }
        }

        return group;
    }

    @Override
    public boolean updateGroup(Long groupId, Long operatorId, GroupUpdateDTO dto) {
        int role = getMemberRole(groupId, operatorId);
        if (role < 1) {
            throw new RuntimeException("只有群主或管理员才能修改群信息");
        }

        ChatGroup group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new RuntimeException("群组不存在");
        }

        if (dto.getGroupName() != null) group.setGroupName(dto.getGroupName());
        if (dto.getAvatar() != null) group.setAvatar(dto.getAvatar());
        if (dto.getAnnouncement() != null) group.setAnnouncement(dto.getAnnouncement());
        if (dto.getJoinMode() != null) group.setJoinMode(dto.getJoinMode());
        group.setUpdateTime(LocalDateTime.now());

        return groupMapper.updateById(group) > 0;
    }

    @Override
    public ChatGroup getGroupInfo(Long groupId) {
        return groupMapper.selectById(groupId);
    }

    @Override
    public List<ChatGroup> getUserGroups(Long userId) {
        // 查询用户加入的所有群
        LambdaQueryWrapper<GroupMember> memberWrapper = new LambdaQueryWrapper<>();
        memberWrapper.eq(GroupMember::getUserId, userId);
        List<GroupMember> members = memberMapper.selectList(memberWrapper);

        if (members.isEmpty()) return Collections.emptyList();

        List<Long> groupIds = members.stream().map(GroupMember::getGroupId).toList();
        LambdaQueryWrapper<ChatGroup> groupWrapper = new LambdaQueryWrapper<>();
        groupWrapper.in(ChatGroup::getId, groupIds);
        return groupMapper.selectList(groupWrapper);
    }

    @Override
    public List<Map<String, Object>> getUserGroupsWithLastMessage(Long userId) {
        List<ChatGroup> groups = getUserGroups(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ChatGroup g : groups) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", g.getId());
            map.put("groupName", g.getGroupName());
            map.put("avatar", g.getAvatar());
            map.put("announcement", g.getAnnouncement());
            map.put("ownerId", g.getOwnerId());
            map.put("maxMembers", g.getMaxMembers());
            map.put("joinMode", g.getJoinMode());
            map.put("createTime", g.getCreateTime());
            map.put("updateTime", g.getUpdateTime());

            // 查询该群最后一条消息
            LambdaQueryWrapper<ChatMessage> msgWrapper = new LambdaQueryWrapper<>();
            msgWrapper.eq(ChatMessage::getGroupId, g.getId())
                    .orderByDesc(ChatMessage::getCreateTime)
                    .last("LIMIT 1");
            ChatMessage lastMsg = chatMessageMapper.selectOne(msgWrapper);

            map.put("lastMessageTime", lastMsg != null ? lastMsg.getCreateTime() : null);
            map.put("lastMessage", lastMsg != null ? lastMsg.getContent() : null);
            map.put("lastMessageType", lastMsg != null ? lastMsg.getType() : null);

            result.add(map);
        }

        // 按最后消息时间倒序
        result.sort((a, b) -> {
            java.time.LocalDateTime timeA = (java.time.LocalDateTime) a.get("lastMessageTime");
            java.time.LocalDateTime timeB = (java.time.LocalDateTime) b.get("lastMessageTime");
            if (timeA == null && timeB == null) return 0;
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            return timeB.compareTo(timeA);
        });

        return result;
    }

    @Override
    public List<Map<String, Object>> getGroupMembers(Long groupId) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId)
                .orderByDesc(GroupMember::getRole);
        List<GroupMember> members = memberMapper.selectList(wrapper);

        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupMember m : members) {
            User user = userMapper.selectById(m.getUserId());
            if (user != null) {
                Map<String, Object> map = new HashMap<>();
                map.put("userId", user.getId());
                map.put("nickname", user.getNickname());
                map.put("avatar", user.getAvatar());
                map.put("role", m.getRole());
                map.put("muteUntil", m.getMuteUntil());
                map.put("joinTime", m.getJoinTime());
                map.put("online", user.getOnline());
                result.add(map);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public boolean addMembers(Long groupId, Long operatorId, List<Long> userIds) {
        int role = getMemberRole(groupId, operatorId);
        if (role < 1) {
            throw new RuntimeException("只有群主或管理员才能添加成员");
        }

        // 检查群人数上限
        ChatGroup group = groupMapper.selectById(groupId);
        long currentCount = getMemberCount(groupId);
        if (currentCount + userIds.size() > group.getMaxMembers()) {
            throw new RuntimeException("群成员数量已达上限");
        }

        for (Long userId : userIds) {
            // 跳过已存在的成员
            if (isMember(groupId, userId)) continue;

            if (userMapper.selectById(userId) != null) {
                GroupMember member = new GroupMember();
                member.setGroupId(groupId);
                member.setUserId(userId);
                member.setRole(0);
                member.setJoinTime(LocalDateTime.now());
                memberMapper.insert(member);
            }
        }
        return true;
    }

    @Override
    public boolean removeMember(Long groupId, Long operatorId, Long userId) {
        int role = getMemberRole(groupId, operatorId);
        if (role < 1) {
            throw new RuntimeException("只有群主或管理员才能移除成员");
        }

        int targetRole = getMemberRole(groupId, userId);
        if (targetRole == 2) {
            throw new RuntimeException("不能移除群主");
        }
        if (targetRole == 1 && role != 2) {
            throw new RuntimeException("管理员只能由群主移除");
        }

        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        return memberMapper.delete(wrapper) > 0;
    }

    @Override
    public boolean leaveGroup(Long groupId, Long userId) {
        int role = getMemberRole(groupId, userId);
        if (role == 2) {
            // 群主退群需要先转让
            throw new RuntimeException("群主请先转让群主身份后再退群");
        }

        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        return memberMapper.delete(wrapper) > 0;
    }

    @Override
    @Transactional
    public boolean transferOwner(Long groupId, Long currentOwnerId, Long newOwnerId) {
        ChatGroup group = groupMapper.selectById(groupId);
        if (group == null || !group.getOwnerId().equals(currentOwnerId)) {
            throw new RuntimeException("只有群主才能转让群主");
        }

        if (!isMember(groupId, newOwnerId)) {
            throw new RuntimeException("新群主必须是群成员");
        }

        // 旧群主变为管理员
        LambdaQueryWrapper<GroupMember> oldOwnerWrapper = new LambdaQueryWrapper<>();
        oldOwnerWrapper.eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, currentOwnerId);
        GroupMember oldOwner = memberMapper.selectOne(oldOwnerWrapper);
        if (oldOwner != null) {
            oldOwner.setRole(1);
            memberMapper.updateById(oldOwner);
        }

        // 新群主
        LambdaQueryWrapper<GroupMember> newOwnerWrapper = new LambdaQueryWrapper<>();
        newOwnerWrapper.eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, newOwnerId);
        GroupMember newOwner = memberMapper.selectOne(newOwnerWrapper);
        if (newOwner != null) {
            newOwner.setRole(2);
            memberMapper.updateById(newOwner);
        }

        // 更新群组表
        group.setOwnerId(newOwnerId);
        group.setUpdateTime(LocalDateTime.now());
        groupMapper.updateById(group);

        return true;
    }

    @Override
    public boolean setAdmin(Long groupId, Long operatorId, Long userId, boolean isAdmin) {
        int role = getMemberRole(groupId, operatorId);
        if (role != 2) {
            throw new RuntimeException("只有群主才能设置管理员");
        }

        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        GroupMember member = memberMapper.selectOne(wrapper);
        if (member == null) {
            throw new RuntimeException("用户不是群成员");
        }
        if (member.getRole() == 2) {
            throw new RuntimeException("不能对群主进行此操作");
        }

        // 设置管理员时检查上限（最多3名）
        if (isAdmin && member.getRole() != 1) {
            long adminCount = getAdminCount(groupId);
            if (adminCount >= MAX_ADMINS) {
                throw new RuntimeException("管理员数量不能超过" + MAX_ADMINS + "名");
            }
        }

        member.setRole(isAdmin ? 1 : 0);
        return memberMapper.updateById(member) > 0;
    }

    @Override
    public boolean muteMember(Long groupId, Long operatorId, Long userId, int minutes) {
        int role = getMemberRole(groupId, operatorId);
        if (role < 1) {
            throw new RuntimeException("只有群主或管理员才能禁言成员");
        }

        int targetRole = getMemberRole(groupId, userId);
        if (targetRole >= role) {
            throw new RuntimeException("不能禁言同级或更高级别的成员");
        }

        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        GroupMember member = memberMapper.selectOne(wrapper);
        if (member == null) {
            throw new RuntimeException("用户不是群成员");
        }

        if (minutes <= 0) {
            member.setMuteUntil(null);
        } else {
            member.setMuteUntil(LocalDateTime.now().plusMinutes(minutes));
        }
        return memberMapper.updateById(member) > 0;
    }

    @Override
    public int getMemberRole(Long groupId, Long userId) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        GroupMember member = memberMapper.selectOne(wrapper);
        return member != null ? member.getRole() : -1;
    }

    @Override
    public boolean isMember(Long groupId, Long userId) {
        return getMemberRole(groupId, userId) >= 0;
    }

    @Override
    @Transactional
    public boolean dissolveGroup(Long groupId, Long operatorId) {
        ChatGroup group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new RuntimeException("群组不存在");
        }
        if (!group.getOwnerId().equals(operatorId)) {
            throw new RuntimeException("只有群主才能解散群聊");
        }

        // 删除所有群成员
        LambdaQueryWrapper<GroupMember> memberWrapper = new LambdaQueryWrapper<>();
        memberWrapper.eq(GroupMember::getGroupId, groupId);
        memberMapper.delete(memberWrapper);

        // 删除群组
        groupMapper.deleteById(groupId);
        return true;
    }

    @Override
    public boolean isMuted(Long groupId, Long userId) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId).eq(GroupMember::getUserId, userId);
        GroupMember member = memberMapper.selectOne(wrapper);
        if (member == null || member.getMuteUntil() == null) return false;
        return member.getMuteUntil().isAfter(LocalDateTime.now());
    }

    @Override
    public long getAdminCount(Long groupId) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getRole, 1);
        return memberMapper.selectCount(wrapper);
    }

    private long getMemberCount(Long groupId) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMember::getGroupId, groupId);
        return memberMapper.selectCount(wrapper);
    }
}
