package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lanchat.dto.ChangePasswordDTO;
import com.lanchat.dto.LoginDTO;
import com.lanchat.dto.LoginVO;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.dto.TokenRefreshDTO;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.security.UserContextHolder;
import com.lanchat.mapper.DeviceLoginMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.LoginAttemptService;
import com.lanchat.service.FileService;
import com.lanchat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import com.lanchat.mapper.FriendshipMapper;
import com.lanchat.mapper.FriendRequestMapper;
import com.lanchat.mapper.GroupMemberMapper;
import com.lanchat.mapper.ChatMessageMapper;
import com.lanchat.mapper.FileMetadataMapper;
import com.lanchat.mapper.MessageRecallMapper;
import com.lanchat.mapper.ChatGroupMapper;
import com.lanchat.entity.Friendship;
import com.lanchat.entity.FriendRequest;
import com.lanchat.entity.GroupMember;
import com.lanchat.entity.ChatMessage;
import com.lanchat.entity.MessageRecall;
import com.lanchat.entity.FileMetadata;
import com.lanchat.entity.ChatGroup;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Set<String> ALLOWED_DEVICE_TYPES = Set.of("web", "desktop", "android", "ios");

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private DeviceLoginMapper deviceLoginMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private FriendshipMapper friendshipMapper;

    @Autowired
    private FriendRequestMapper friendRequestMapper;

    @Autowired
    private GroupMemberMapper groupMemberMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private MessageRecallMapper messageRecallMapper;

    @Autowired
    private ChatGroupMapper chatGroupMapper;

    @Autowired
    private FileService fileService;

    @Override
    public boolean register(RegisterDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getUsername()) || !StringUtils.hasText(dto.getPassword())) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }

        String username = dto.getUsername().trim();
        if (username.length() < 3 || username.length() > 50
                || !username.matches("^[a-zA-Z0-9_.@-]+$")) {
            throw new IllegalArgumentException("用户名需为3-50位字母、数字或 ._@-");
        }
        if ("admin".equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("admin 为系统保留账号，不能通过注册接口创建");
        }

        // 密码强度校验：8-20位，含字母和数字
        String password = dto.getPassword();
        validateAccountPassword(password);

        // 昵称长度校验：2-16字符
        if (StringUtils.hasText(dto.getNickname())) {
            if (dto.getNickname().length() < 2 || dto.getNickname().length() > 16) {
                throw new IllegalArgumentException("昵称长度需为2-16字符");
            }
        }

        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        if (userMapper.selectCount(wrapper) > 0) {
            return false;
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setAvatar("");
        user.setSignature("");
        user.setOnline(0);
        user.setStatus(1);
        user.setCanSendBroadcast(0);
        user.setCreateTime(LocalDateTime.now());

        return userMapper.insert(user) > 0;
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getUsername()) || !StringUtils.hasText(dto.getPassword())) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        if (dto.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 检查是否被锁定
        if (loginAttemptService.isLocked(dto.getUsername())) {
            long remaining = loginAttemptService.getRemainingLockTime(dto.getUsername());
            throw new IllegalArgumentException("账号已被锁定，请" + remaining + "分钟后重试或找回密码");
        }

        // 查找用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        User user = userMapper.selectOne(wrapper);

        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            // 记录失败尝试
            loginAttemptService.recordFailedAttempt(dto.getUsername());
            int remaining = 5 - loginAttemptService.getFailedAttempts(dto.getUsername());
            if (remaining > 0) {
                throw new IllegalArgumentException("用户名或密码错误，剩余尝试次数：" + remaining);
            }
            return null;
        }

        // 检查账号状态
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new IllegalArgumentException("账号已被锁定，请联系管理员");
        }

        // 登录成功，清除失败计数
        loginAttemptService.clearAttempts(dto.getUsername());

        String requestedDeviceType = StringUtils.hasText(dto.getDeviceType())
                ? dto.getDeviceType().trim().toLowerCase() : "web";
        String deviceType = ALLOWED_DEVICE_TYPES.contains(requestedDeviceType) ? requestedDeviceType : "web";
        String deviceName = StringUtils.hasText(dto.getDeviceName())
                ? dto.getDeviceName().trim().substring(0, Math.min(dto.getDeviceName().trim().length(), 100))
                : deviceType;

        // 同类型设备互踢逻辑
        // PRD: 同类型终端后登录者提示"已在其他设备登录"，可选择强制下线旧设备
        invalidateSameDeviceType(user.getId(), deviceType);

        // 生成 Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), deviceType);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), deviceType);

        // 保存设备登录记录
        DeviceLogin deviceLogin = new DeviceLogin();
        deviceLogin.setUserId(user.getId());
        deviceLogin.setDeviceType(deviceType);
        deviceLogin.setDeviceName(deviceName);
        deviceLogin.setToken(token);
        deviceLogin.setRefreshToken(refreshToken);
        deviceLogin.setLoginTime(LocalDateTime.now());
        deviceLogin.setExpireTime(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshExpiration() / 1000));
        deviceLogin.setStatus(1);
        deviceLoginMapper.insert(deviceLogin);

        // 在线状态只由 WebSocket 连接维护，登录接口仅记录最后登录时间。
        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(User::getId, user.getId())
                .set(User::getLastLoginAt, LocalDateTime.now());
        userMapper.update(null, updateWrapper);

        // 构建返回结果
        LoginVO vo = new LoginVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setToken(token);
        vo.setRefreshToken(refreshToken);
        vo.setExpiresIn(jwtUtil.getExpiration() / 1000);
        return vo;
    }

    @Override
    public LoginVO refreshToken(TokenRefreshDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getRefreshToken())
                || !jwtUtil.isRefreshToken(dto.getRefreshToken())) {
            return null;
        }

        Long userId = jwtUtil.getUserIdFromToken(dto.getRefreshToken());
        User user = userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            return null;
        }

        String deviceType = jwtUtil.getDeviceTypeFromToken(dto.getRefreshToken());
        if (!ALLOWED_DEVICE_TYPES.contains(deviceType)) {
            return null;
        }

        LambdaQueryWrapper<DeviceLogin> deviceWrapper = new LambdaQueryWrapper<>();
        deviceWrapper.eq(DeviceLogin::getUserId, userId)
                .eq(DeviceLogin::getDeviceType, deviceType)
                .eq(DeviceLogin::getRefreshToken, dto.getRefreshToken())
                .eq(DeviceLogin::getStatus, 1)
                .gt(DeviceLogin::getExpireTime, LocalDateTime.now())
                .last("LIMIT 1");
        DeviceLogin device = deviceLoginMapper.selectOne(deviceWrapper);
        if (device == null) {
            return null;
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), deviceType);
        String newRefreshToken = jwtUtil.generateRefreshToken(user.getId(), deviceType);

        // 条件更新保证并发刷新时只有一个请求能消费旧令牌。
        LambdaUpdateWrapper<DeviceLogin> rotateWrapper = new LambdaUpdateWrapper<>();
        rotateWrapper.eq(DeviceLogin::getId, device.getId())
                .eq(DeviceLogin::getRefreshToken, dto.getRefreshToken())
                .eq(DeviceLogin::getStatus, 1)
                .set(DeviceLogin::getToken, token)
                .set(DeviceLogin::getRefreshToken, newRefreshToken)
                .set(DeviceLogin::getExpireTime,
                        LocalDateTime.now().plusSeconds(jwtUtil.getRefreshExpiration() / 1000));
        if (StringUtils.hasText(dto.getDeviceName())) {
            String name = dto.getDeviceName().trim();
            rotateWrapper.set(DeviceLogin::getDeviceName,
                    name.substring(0, Math.min(name.length(), 100)));
        }
        if (deviceLoginMapper.update(null, rotateWrapper) != 1) {
            return null;
        }

        LoginVO vo = new LoginVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setToken(token);
        vo.setRefreshToken(newRefreshToken);
        vo.setExpiresIn(jwtUtil.getExpiration() / 1000);
        return vo;
    }

    @Override
    public void updateOnlineStatus(Long userId, Integer online) {
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
                .set(User::getOnline, online);
        userMapper.update(null, wrapper);
    }

    @Override
    public List<User> getOnlineUsers() {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getOnline, 1)
                .eq(User::getStatus, 1);
        return userMapper.selectList(wrapper);
    }

    @Override
    public User getUserInfo(Long userId) {
        return userMapper.selectById(userId);
    }

    @Override
    public List<User> searchUsers(String keyword) {
        if (!StringUtils.hasText(keyword)) return List.of();
        String trimmed = keyword.trim();
        final String value = trimmed.length() > 50 ? trimmed.substring(0, 50) : trimmed;
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(User::getUsername, value)
                .or()
                .like(User::getNickname, value))
                .ne(User::getStatus, 0);
        return userMapper.selectList(wrapper);
    }

    @Override
    public List<DeviceLogin> getDevices(Long userId) {
        LambdaQueryWrapper<DeviceLogin> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DeviceLogin::getUserId, userId)
                .eq(DeviceLogin::getStatus, 1)
                .orderByDesc(DeviceLogin::getLoginTime);
        return deviceLoginMapper.selectList(wrapper);
    }

    @Override
    public void logoutDevice(Long userId, Long deviceId) {
        LambdaUpdateWrapper<DeviceLogin> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(DeviceLogin::getId, deviceId)
                .eq(DeviceLogin::getUserId, userId)
                .set(DeviceLogin::getStatus, 0);
        deviceLoginMapper.update(null, wrapper);
    }

    @Override
    public DeviceLogin getCurrentDevice(Long userId, String deviceType) {
        LambdaQueryWrapper<DeviceLogin> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DeviceLogin::getUserId, userId)
                .eq(DeviceLogin::getDeviceType, deviceType)
                .eq(DeviceLogin::getStatus, 1)
                .orderByDesc(DeviceLogin::getLoginTime)
                .last("LIMIT 1");
        return deviceLoginMapper.selectOne(wrapper);
    }

    @Override
    public boolean isAccessTokenActive(String token, Long userId, String deviceType) {
        if (!StringUtils.hasText(token) || userId == null || !StringUtils.hasText(deviceType)) {
            return false;
        }

        User user = userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            return false;
        }

        return getActiveDevice(token, userId, deviceType) != null;
    }

    @Override
    public DeviceLogin getActiveDevice(String token, Long userId, String deviceType) {
        if (!StringUtils.hasText(token) || userId == null || !StringUtils.hasText(deviceType)) {
            return null;
        }
        LambdaQueryWrapper<DeviceLogin> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DeviceLogin::getUserId, userId)
                .eq(DeviceLogin::getDeviceType, deviceType)
                .eq(DeviceLogin::getToken, token)
                .eq(DeviceLogin::getStatus, 1)
                .gt(DeviceLogin::getExpireTime, LocalDateTime.now())
                .last("LIMIT 1");
        return deviceLoginMapper.selectOne(wrapper);
    }

    @Override
    public void logoutByToken(Long userId, String token) {
        if (userId == null || !StringUtils.hasText(token)) return;
        LambdaUpdateWrapper<DeviceLogin> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(DeviceLogin::getUserId, userId)
                .eq(DeviceLogin::getToken, token)
                .eq(DeviceLogin::getStatus, 1)
                .set(DeviceLogin::getStatus, 0);
        deviceLoginMapper.update(null, wrapper);
    }

    @Override
    public void logoutByRefreshToken(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) return;
        LambdaUpdateWrapper<DeviceLogin> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(DeviceLogin::getRefreshToken, refreshToken)
                .eq(DeviceLogin::getStatus, 1)
                .set(DeviceLogin::getStatus, 0);
        deviceLoginMapper.update(null, wrapper);
    }

    /**
     * 使同类型设备的旧 Token 失效（互踢逻辑）
     * 同类型终端后登录者踢掉前一个，被踢设备收到强制下线通知
     */
    private void invalidateSameDeviceType(Long userId, String deviceType) {
        LambdaUpdateWrapper<DeviceLogin> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(DeviceLogin::getUserId, userId)
                .eq(DeviceLogin::getDeviceType, deviceType)
                .eq(DeviceLogin::getStatus, 1)
                .set(DeviceLogin::getStatus, 0);
        deviceLoginMapper.update(null, wrapper);
    }

    @Override
    public boolean setMutePeriod(Long userId, String muteStart, String muteEnd) {
        boolean clear = !StringUtils.hasText(muteStart) && !StringUtils.hasText(muteEnd);
        if (!clear && (!isValidTime(muteStart) || !isValidTime(muteEnd))) {
            throw new IllegalArgumentException("免打扰时段格式需为 HH:mm");
        }
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
                .set(User::getMuteStart, clear ? null : muteStart)
                .set(User::getMuteEnd, clear ? null : muteEnd);
        return userMapper.update(null, wrapper) > 0;
    }

    /**
     * 检查用户当前是否处于免打扰时段
     * PRD: 全局免打扰时段设置（如22:00-8:00），期间不弹推送，仅留存未读计数
     */
    @Override
    public boolean isInMutePeriod(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getMuteStart() == null || user.getMuteEnd() == null) {
            return false;
        }

        java.time.LocalTime now = java.time.LocalTime.now();
        java.time.LocalTime start = java.time.LocalTime.parse(user.getMuteStart());
        java.time.LocalTime end = java.time.LocalTime.parse(user.getMuteEnd());

        // 处理跨天的情况（如 22:00 - 08:00）
        if (start.isBefore(end)) {
            // 同一天内（如 09:00 - 12:00）
            return !now.isBefore(start) && now.isBefore(end);
        } else {
            // 跨天（如 22:00 - 08:00）
            return !now.isBefore(start) || now.isBefore(end);
        }
    }

    @Override
    @Transactional
    public boolean deleteUserByAdmin(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if ("admin".equals(user.getUsername())) {
            throw new IllegalArgumentException("不能删除管理员账号");
        }

        List<ChatGroup> ownedGroups = chatGroupMapper.selectList(new LambdaQueryWrapper<ChatGroup>()
                .eq(ChatGroup::getOwnerId, userId));
        List<Long> ownedGroupIds = ownedGroups.stream().map(ChatGroup::getId).toList();

        LambdaQueryWrapper<ChatMessage> affectedMessageWrapper = new LambdaQueryWrapper<>();
        affectedMessageWrapper.and(w -> {
            w.eq(ChatMessage::getFromUserId, userId)
                    .or()
                    .eq(ChatMessage::getToUserId, userId);
            if (!ownedGroupIds.isEmpty()) w.or().in(ChatMessage::getGroupId, ownedGroupIds);
        });
        List<ChatMessage> affectedMessages = chatMessageMapper.selectList(affectedMessageWrapper);
        List<String> affectedMessageIds = affectedMessages.stream()
                .map(ChatMessage::getMessageId)
                .filter(Objects::nonNull)
                .toList();

        // 清理用户相关的所有关联数据
        // 1. 好友关系（作为用户 或 作为好友）
        friendshipMapper.delete(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getUserId, userId)
                .or()
                .eq(Friendship::getFriendId, userId));
        // 2. 好友请求（发出方 或 接收方）
        friendRequestMapper.delete(new LambdaQueryWrapper<FriendRequest>()
                .eq(FriendRequest::getFromUserId, userId)
                .or()
                .eq(FriendRequest::getToUserId, userId));
        // 3. 群成员关系（含该用户拥有并将被解散的群）
        LambdaQueryWrapper<GroupMember> memberDelete = new LambdaQueryWrapper<>();
        memberDelete.eq(GroupMember::getUserId, userId);
        if (!ownedGroupIds.isEmpty()) memberDelete.or().in(GroupMember::getGroupId, ownedGroupIds);
        groupMemberMapper.delete(memberDelete);
        // 4. 设备登录记录
        deviceLoginMapper.delete(new LambdaQueryWrapper<DeviceLogin>()
                .eq(DeviceLogin::getUserId, userId));
        // 5. 消息撤回记录与聊天消息
        LambdaQueryWrapper<MessageRecall> recallDelete = new LambdaQueryWrapper<>();
        recallDelete.eq(MessageRecall::getOperatorId, userId);
        if (!affectedMessageIds.isEmpty()) recallDelete.or().in(MessageRecall::getMessageId, affectedMessageIds);
        messageRecallMapper.delete(recallDelete);
        chatMessageMapper.delete(affectedMessageWrapper);

        // 6. 文件：仍被其他消息引用的秒传文件保留，否则连同缩略图一并清理。
        List<FileMetadata> uploadedFiles = fileMetadataMapper.selectList(new LambdaQueryWrapper<FileMetadata>()
                .eq(FileMetadata::getUploadUserId, userId));
        for (FileMetadata metadata : uploadedFiles) {
            String storedName = metadata.getFilePath();
            long references = storedName == null ? 0 : chatMessageMapper.selectCount(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getFilePath, storedName)
                            .eq(ChatMessage::getIsRecalled, 0));
            if (references > 0) continue;
            fileService.deleteStoredObjects(metadata);
            fileMetadataMapper.deleteById(metadata.getId());
        }

        // 7. 删除用户拥有的群组
        if (!ownedGroupIds.isEmpty()) {
            chatGroupMapper.delete(new LambdaQueryWrapper<ChatGroup>().in(ChatGroup::getId, ownedGroupIds));
        }
        // 8. 删除用户本身
        userMapper.deleteById(userId);
        return true;
    }

    @Override
    @Transactional
    public boolean resetPasswordByAdmin(Long userId, String newPassword) {
        if (userId == null) {
            throw new IllegalArgumentException("用户参数不能为空");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if ("admin".equals(user.getUsername())) {
            throw new IllegalArgumentException("管理员账号请使用“修改密码”功能");
        }

        validateAccountPassword(newPassword);

        LambdaUpdateWrapper<User> userWrapper = new LambdaUpdateWrapper<>();
        userWrapper.eq(User::getId, userId)
                .set(User::getPassword, passwordEncoder.encode(newPassword));
        boolean updated = userMapper.update(null, userWrapper) > 0;
        if (!updated) return false;

        LambdaUpdateWrapper<DeviceLogin> deviceWrapper = new LambdaUpdateWrapper<>();
        deviceWrapper.eq(DeviceLogin::getUserId, userId)
                .eq(DeviceLogin::getStatus, 1)
                .set(DeviceLogin::getStatus, 0);
        deviceLoginMapper.update(null, deviceWrapper);
        loginAttemptService.clearAttempts(user.getUsername());
        return true;
    }

    @Override
    public boolean setBroadcastPermission(Long userId, boolean enabled) {
        if (userId == null) {
            throw new IllegalArgumentException("用户参数不能为空");
        }
        User target = userMapper.selectById(userId);
        if (target == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if ("admin".equals(target.getUsername())) {
            throw new IllegalArgumentException("管理员默认拥有广播权限，不能修改");
        }

        User update = new User();
        update.setId(userId);
        update.setCanSendBroadcast(enabled ? 1 : 0);
        return userMapper.updateById(update) > 0;
    }

    @Override
    public boolean updateProfile(Long userId, String nickname, String avatar) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId);

        if (nickname != null) {
            String trimmed = nickname.trim();
            if (trimmed.length() < 1 || trimmed.length() > 16) {
                throw new IllegalArgumentException("昵称长度需为1-16字符");
            }
            wrapper.set(User::getNickname, trimmed);
        }

        if (avatar != null) {
            String value = avatar.trim();
            if (value.length() > 255) {
                throw new IllegalArgumentException("头像地址过长");
            }
            wrapper.set(User::getAvatar, value);
        }

        return userMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean changePassword(ChangePasswordDTO dto) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            throw new IllegalArgumentException("用户未登录");
        }

        if (dto == null || !StringUtils.hasText(dto.getOldPassword())
                || !StringUtils.hasText(dto.getNewPassword())) {
            throw new IllegalArgumentException("密码参数不完整");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        // 校验旧密码
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new IllegalArgumentException("原密码错误");
        }

        // 校验新密码强度（复用注册时的密码校验逻辑）
        String newPassword = dto.getNewPassword();
        validateAccountPassword(newPassword);

        // 更新密码并撤销所有设备会话，避免旧令牌继续访问。
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
                .set(User::getPassword, passwordEncoder.encode(newPassword));
        boolean updated = userMapper.update(null, wrapper) > 0;
        if (updated) {
            LambdaUpdateWrapper<DeviceLogin> deviceWrapper = new LambdaUpdateWrapper<>();
            deviceWrapper.eq(DeviceLogin::getUserId, userId)
                    .eq(DeviceLogin::getStatus, 1)
                    .set(DeviceLogin::getStatus, 0);
            deviceLoginMapper.update(null, deviceWrapper);
        }
        return updated;
    }

    private void validateAccountPassword(String password) {
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        if (password.length() < 8 || password.length() > 20) {
            throw new IllegalArgumentException("密码长度需为8-20位");
        }
        if (!password.matches(".*[a-zA-Z]+.*") || !password.matches(".*\\d+.*")) {
            throw new IllegalArgumentException("密码必须包含字母和数字");
        }
    }

    private boolean isValidTime(String value) {
        if (!StringUtils.hasText(value) || !value.matches("^(?:[01]\\d|2[0-3]):[0-5]\\d$")) {
            return false;
        }
        try {
            java.time.LocalTime.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }
}
