package com.lanchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lanchat.entity.FriendRequest;
import com.lanchat.entity.Friendship;
import com.lanchat.entity.User;

import java.util.List;
import java.util.Map;

public interface FriendService extends IService<Friendship> {

    /** 发送好友申请 */
    boolean sendFriendRequest(Long fromUserId, Long toUserId, String message);

    /** 处理好友申请（同意/拒绝） */
    boolean handleFriendRequest(Long requestId, Long currentUserId, boolean accept);

    /** 获取好友申请列表 */
    List<FriendRequest> getFriendRequests(Long userId);

    /** 获取好友列表 */
    List<Friendship> getFriendList(Long userId);

    /** 获取好友列表（含用户详细信息） */
    List<Map<String, Object>> getFriendListWithInfo(Long userId);

    /** 删除好友 */
    boolean deleteFriend(Long userId, Long friendId);

    /** 拉黑/取消拉黑好友 */
    boolean toggleBlock(Long userId, Long friendId);

    /** 设置好友备注 */
    boolean setRemark(Long userId, Long friendId, String remark);

    /** 设置好友分组 */
    boolean setGroup(Long userId, Long friendId, String groupName);

    /** 设置置顶 */
    boolean togglePin(Long userId, Long friendId);

    /** 设置免打扰 */
    boolean toggleMute(Long userId, Long friendId);

    /** 判断是否为好友 */
    boolean isFriend(Long userId, Long friendId);

    /** 检查是否被对方拉黑 */
    boolean isBlockedBy(Long userId, Long friendId);
}
