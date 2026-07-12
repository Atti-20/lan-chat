package com.lanchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lanchat.dto.GroupCreateDTO;
import com.lanchat.dto.GroupUpdateDTO;
import com.lanchat.entity.ChatGroup;
import com.lanchat.entity.GroupMember;

import java.util.List;
import java.util.Map;

public interface GroupService extends IService<ChatGroup> {

    /** 创建群组 */
    ChatGroup createGroup(Long ownerId, GroupCreateDTO dto);

    /** 更新群组信息 */
    boolean updateGroup(Long groupId, Long operatorId, GroupUpdateDTO dto);

    /** 获取群组信息 */
    ChatGroup getGroupInfo(Long groupId);

    /** 获取用户加入的群组列表 */
    List<ChatGroup> getUserGroups(Long userId);

    /** 获取群成员列表 */
    List<Map<String, Object>> getGroupMembers(Long groupId);

    /** 添加群成员 */
    boolean addMembers(Long groupId, Long operatorId, List<Long> userIds);

    /** 移除群成员 */
    boolean removeMember(Long groupId, Long operatorId, Long userId);

    /** 主动退群 */
    boolean leaveGroup(Long groupId, Long userId);

    /** 转让群主 */
    boolean transferOwner(Long groupId, Long currentOwnerId, Long newOwnerId);

    /** 设置管理员 */
    boolean setAdmin(Long groupId, Long operatorId, Long userId, boolean isAdmin);

    /** 禁言成员 */
    boolean muteMember(Long groupId, Long operatorId, Long userId, int minutes);

    /** 检查用户在群中的角色 */
    int getMemberRole(Long groupId, Long userId);

    /** 检查是否为群成员 */
    boolean isMember(Long groupId, Long userId);

    /** 解散群聊（仅群主） */
    boolean dissolveGroup(Long groupId, Long operatorId);

    /** 检查成员是否被禁言 */
    boolean isMuted(Long groupId, Long userId);

    /** 获取群管理员数量 */
    long getAdminCount(Long groupId);
}
