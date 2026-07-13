package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.FriendRequest;
import com.lanchat.entity.Friendship;
import com.lanchat.entity.User;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.FriendRequestMapper;
import com.lanchat.mapper.FriendshipMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.FriendService;
import com.lanchat.websocket.ChatWebSocketHandler;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FriendServiceImpl extends ServiceImpl<FriendshipMapper, Friendship> implements FriendService {

    /** 好友申请有效期：7天 */
    private static final int REQUEST_EXPIRE_DAYS = 7;

    /** 最大置顶数量 */
    private static final int MAX_PINNED = 5;

    /** 好友申请验证信息最大长度 */
    private static final int MAX_MESSAGE_LENGTH = 20;

    @Autowired
    private FriendRequestMapper friendRequestMapper;

    @Autowired
    private FriendshipMapper friendshipMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    @Lazy
    private ChatWebSocketHandler webSocketHandler;

    @Override
    public boolean sendFriendRequest(Long fromUserId, Long toUserId, String message) {
        if (fromUserId.equals(toUserId)) {
            throw new IllegalArgumentException("不能添加自己为好友");
        }

        // 检查目标用户是否存在
        User targetUser = userMapper.selectById(toUserId);
        if (targetUser == null) {
            throw new IllegalArgumentException("目标用户不存在");
        }

        // 检查是否已经是好友
        if (isFriend(fromUserId, toUserId)) {
            throw new IllegalArgumentException("你们已经是好友了");
        }

        // 黑名单互斥校验：如果任一方拉黑了对方，不允许申请
        Friendship fromRelation = getFriendship(fromUserId, toUserId);
        if (fromRelation != null && fromRelation.getIsBlocked() == 1) {
            throw new IllegalArgumentException("您已拉黑对方，请先移出黑名单");
        }
        Friendship toRelation = getFriendship(toUserId, fromUserId);
        if (toRelation != null && toRelation.getIsBlocked() == 1) {
            throw new IllegalArgumentException("对方暂不接受好友申请");
        }

        // 清理过期的申请（7天自动过期）
        expireOldRequests(fromUserId, toUserId);

        // 检查是否已有待处理的申请
        LambdaQueryWrapper<FriendRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FriendRequest::getFromUserId, fromUserId)
                .eq(FriendRequest::getToUserId, toUserId)
                .eq(FriendRequest::getStatus, 0);
        if (friendRequestMapper.selectCount(wrapper) > 0) {
            throw new IllegalArgumentException("已发送过好友申请，请等待对方处理");
        }

        // 验证信息长度限制（20字内）
        String msg = message != null ? message : "";
        if (msg.length() > MAX_MESSAGE_LENGTH) {
            msg = msg.substring(0, MAX_MESSAGE_LENGTH);
        }

        FriendRequest request = new FriendRequest();
        request.setFromUserId(fromUserId);
        request.setToUserId(toUserId);
        request.setMessage(msg);
        request.setStatus(0);
        request.setCreateTime(LocalDateTime.now());
        return friendRequestMapper.insert(request) > 0;
    }

    @Override
    @Transactional
    public boolean handleFriendRequest(Long requestId, Long currentUserId, boolean accept) {
        FriendRequest request = friendRequestMapper.selectById(requestId);
        if (request == null || request.getStatus() != 0) {
            throw new IllegalArgumentException("好友申请不存在或已处理");
        }

        // 检查申请是否已过期（7天）
        if (request.getCreateTime() != null &&
            request.getCreateTime().plusDays(REQUEST_EXPIRE_DAYS).isBefore(LocalDateTime.now())) {
            LambdaUpdateWrapper<FriendRequest> expireWrapper = new LambdaUpdateWrapper<>();
            expireWrapper.eq(FriendRequest::getId, requestId)
                    .set(FriendRequest::getStatus, 2)
                    .set(FriendRequest::getHandleTime, LocalDateTime.now());
            friendRequestMapper.update(null, expireWrapper);
            throw new IllegalArgumentException("好友申请已过期");
        }

        if (!request.getToUserId().equals(currentUserId)) {
            throw new IllegalArgumentException("无权处理此申请");
        }

        // 更新申请状态
        LambdaUpdateWrapper<FriendRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(FriendRequest::getId, requestId)
                .set(FriendRequest::getStatus, accept ? 1 : 2)
                .set(FriendRequest::getHandleTime, LocalDateTime.now());
        friendRequestMapper.update(null, updateWrapper);

        if (accept) {
            // 发送 WebSocket 通知给原申请者
            User acceptor = userMapper.selectById(currentUserId);
            if (acceptor != null) {
                String notification = acceptor.getNickname() + " 已接受你的好友请求";
                webSocketHandler.sendFriendNotification(request.getFromUserId(), notification);
            }

            // 双向添加好友关系
            Friendship f1 = new Friendship();
            f1.setUserId(request.getFromUserId());
            f1.setFriendId(request.getToUserId());
            f1.setRemark("");
            f1.setGroupName("我的好友");
            f1.setIsBlocked(0);
            f1.setIsMuted(0);
            f1.setIsPinned(0);
            f1.setCreateTime(LocalDateTime.now());
            friendshipMapper.insert(f1);

            Friendship f2 = new Friendship();
            f2.setUserId(request.getToUserId());
            f2.setFriendId(request.getFromUserId());
            f2.setRemark("");
            f2.setGroupName("我的好友");
            f2.setIsBlocked(0);
            f2.setIsMuted(0);
            f2.setIsPinned(0);
            f2.setCreateTime(LocalDateTime.now());
            friendshipMapper.insert(f2);
        }

        return true;
    }

    @Override
    public List<FriendRequest> getFriendRequests(Long userId) {
        // 先清理过期申请
        expireOldRequestsForUser(userId);

        LambdaQueryWrapper<FriendRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FriendRequest::getToUserId, userId)
                .eq(FriendRequest::getStatus, 0)
                .orderByDesc(FriendRequest::getCreateTime);
        return friendRequestMapper.selectList(wrapper);
    }

    @Override
    public List<Friendship> getFriendList(Long userId) {
        LambdaQueryWrapper<Friendship> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friendship::getUserId, userId)
                .eq(Friendship::getIsBlocked, 0)
                .orderByDesc(Friendship::getIsPinned);
        return friendshipMapper.selectList(wrapper);
    }

    @Override
    public List<Map<String, Object>> getFriendListWithInfo(Long userId) {
        List<Friendship> friendships = getFriendList(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Friendship f : friendships) {
            User user = userMapper.selectById(f.getFriendId());
            if (user != null) {
                // 查询最后通信时间（最后一条私聊消息）
                LambdaQueryWrapper<ChatMessage> msgWrapper = new LambdaQueryWrapper<>();
                msgWrapper.and(w -> w
                                .and(w1 -> w1.eq(ChatMessage::getFromUserId, userId).eq(ChatMessage::getToUserId, f.getFriendId()))
                        .or(w2 -> w2.eq(ChatMessage::getFromUserId, f.getFriendId()).eq(ChatMessage::getToUserId, userId)))
                        .orderByDesc(ChatMessage::getCreateTime)
                        .last("LIMIT 1");
                ChatMessage lastMsg = chatMessageMapper.selectOne(msgWrapper);

                Map<String, Object> map = new HashMap<>();
                map.put("friendId", user.getId());
                map.put("username", user.getUsername());
                map.put("nickname", user.getNickname());
                map.put("avatar", user.getAvatar());
                map.put("signature", user.getSignature());
                map.put("online", user.getOnline());
                map.put("remark", f.getRemark());
                map.put("groupName", f.getGroupName());
                map.put("isPinned", f.getIsPinned());
                map.put("isMuted", f.getIsMuted());
                map.put("lastLoginAt", user.getLastLoginAt());
                map.put("lastMessageTime", lastMsg != null ? lastMsg.getCreateTime() : null);
                map.put("lastMessage", lastMsg != null ? lastMsg.getContent() : null);
                map.put("lastMessageType", lastMsg != null ? lastMsg.getType() : null);
                result.add(map);
            }
        }

        // 按最后通信时间倒序，置顶优先
        result.sort((a, b) -> {
            int pinA = (int) a.get("isPinned");
            int pinB = (int) b.get("isPinned");
            if (pinA != pinB) return pinB - pinA;
            LocalDateTime timeA = (LocalDateTime) a.get("lastMessageTime");
            LocalDateTime timeB = (LocalDateTime) b.get("lastMessageTime");
            if (timeA == null && timeB == null) return 0;
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            return timeB.compareTo(timeA);
        });

        return result;
    }

    @Override
    @Transactional
    public boolean deleteFriend(Long userId, Long friendId) {
        // 双向删除好友关系
        LambdaQueryWrapper<Friendship> wrapper1 = new LambdaQueryWrapper<>();
        wrapper1.eq(Friendship::getUserId, userId).eq(Friendship::getFriendId, friendId);
        friendshipMapper.delete(wrapper1);

        LambdaQueryWrapper<Friendship> wrapper2 = new LambdaQueryWrapper<>();
        wrapper2.eq(Friendship::getUserId, friendId).eq(Friendship::getFriendId, userId);
        friendshipMapper.delete(wrapper2);
        // 聊天记录保留但不可再发消息（除非重新添加）
        return true;
    }

    @Override
    public boolean toggleBlock(Long userId, Long friendId) {
        Friendship friendship = getFriendship(userId, friendId);
        if (friendship == null) return false;
        LambdaUpdateWrapper<Friendship> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Friendship::getId, friendship.getId())
                .set(Friendship::getIsBlocked, friendship.getIsBlocked() == 0 ? 1 : 0);
        return friendshipMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean setRemark(Long userId, Long friendId, String remark) {
        LambdaUpdateWrapper<Friendship> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Friendship::getUserId, userId)
                .eq(Friendship::getFriendId, friendId)
                .set(Friendship::getRemark, remark);
        return friendshipMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean setGroup(Long userId, Long friendId, String groupName) {
        LambdaUpdateWrapper<Friendship> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Friendship::getUserId, userId)
                .eq(Friendship::getFriendId, friendId)
                .set(Friendship::getGroupName, groupName);
        return friendshipMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean togglePin(Long userId, Long friendId) {
        Friendship friendship = getFriendship(userId, friendId);
        if (friendship == null) return false;

        // 如果要置顶，检查是否超过上限（最多5个）
        if (friendship.getIsPinned() == 0) {
            LambdaQueryWrapper<Friendship> countWrapper = new LambdaQueryWrapper<>();
            countWrapper.eq(Friendship::getUserId, userId)
                    .eq(Friendship::getIsPinned, 1);
            long pinnedCount = friendshipMapper.selectCount(countWrapper);
            if (pinnedCount >= MAX_PINNED) {
                throw new RuntimeException("置顶数量不能超过" + MAX_PINNED + "个");
            }
        }

        LambdaUpdateWrapper<Friendship> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Friendship::getId, friendship.getId())
                .set(Friendship::getIsPinned, friendship.getIsPinned() == 0 ? 1 : 0);
        return friendshipMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean toggleMute(Long userId, Long friendId) {
        Friendship friendship = getFriendship(userId, friendId);
        if (friendship == null) return false;
        LambdaUpdateWrapper<Friendship> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Friendship::getId, friendship.getId())
                .set(Friendship::getIsMuted, friendship.getIsMuted() == 0 ? 1 : 0);
        return friendshipMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean isFriend(Long userId, Long friendId) {
        LambdaQueryWrapper<Friendship> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friendship::getUserId, userId)
                .eq(Friendship::getFriendId, friendId)
                .eq(Friendship::getIsBlocked, 0);
        return friendshipMapper.selectCount(wrapper) > 0;
    }

    /**
     * 检查是否被对方拉黑
     */
    @Override
    public boolean isBlockedBy(Long userId, Long friendId) {
        Friendship friendship = getFriendship(friendId, userId);
        return friendship != null && friendship.getIsBlocked() == 1;
    }

    private Friendship getFriendship(Long userId, Long friendId) {
        LambdaQueryWrapper<Friendship> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Friendship::getUserId, userId)
                .eq(Friendship::getFriendId, friendId);
        return friendshipMapper.selectOne(wrapper);
    }

    /**
     * 清理指定用户间过期的好友申请（7天）
     */
    private void expireOldRequests(Long fromUserId, Long toUserId) {
        LocalDateTime expireTime = LocalDateTime.now().minusDays(REQUEST_EXPIRE_DAYS);
        LambdaQueryWrapper<FriendRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FriendRequest::getFromUserId, fromUserId)
                .eq(FriendRequest::getToUserId, toUserId)
                .eq(FriendRequest::getStatus, 0)
                .lt(FriendRequest::getCreateTime, expireTime);
        List<FriendRequest> expired = friendRequestMapper.selectList(wrapper);
        for (FriendRequest req : expired) {
            LambdaUpdateWrapper<FriendRequest> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(FriendRequest::getId, req.getId())
                    .set(FriendRequest::getStatus, 2)
                    .set(FriendRequest::getHandleTime, LocalDateTime.now());
            friendRequestMapper.update(null, updateWrapper);
        }
    }

    /**
     * 清理指定用户收到的过期申请
     */
    private void expireOldRequestsForUser(Long userId) {
        LocalDateTime expireTime = LocalDateTime.now().minusDays(REQUEST_EXPIRE_DAYS);
        LambdaQueryWrapper<FriendRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FriendRequest::getToUserId, userId)
                .eq(FriendRequest::getStatus, 0)
                .lt(FriendRequest::getCreateTime, expireTime);
        List<FriendRequest> expired = friendRequestMapper.selectList(wrapper);
        for (FriendRequest req : expired) {
            LambdaUpdateWrapper<FriendRequest> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(FriendRequest::getId, req.getId())
                    .set(FriendRequest::getStatus, 2)
                    .set(FriendRequest::getHandleTime, LocalDateTime.now());
            friendRequestMapper.update(null, updateWrapper);
        }
    }
}
