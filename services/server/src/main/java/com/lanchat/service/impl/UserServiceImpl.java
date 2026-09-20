package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lanchat.common.ConversationIds;
import com.lanchat.common.DeviceSessionsRevokedEvent;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.dto.ChangePasswordDTO;
import com.lanchat.dto.LoginDTO;
import com.lanchat.dto.LoginVO;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.dto.TokenRefreshDTO;
import com.lanchat.entity.AdminUserLifecycleAudit;
import com.lanchat.entity.ChatGroup;
import com.lanchat.entity.ConversationMember;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.FileAccessGrant;
import com.lanchat.entity.FileMetadata;
import com.lanchat.entity.FriendRequest;
import com.lanchat.entity.Friendship;
import com.lanchat.entity.GroupMember;
import com.lanchat.entity.TemporaryRoom;
import com.lanchat.entity.User;
import com.lanchat.mapper.AdminUserLifecycleAuditMapper;
import com.lanchat.mapper.ChatGroupMapper;
import com.lanchat.mapper.ConversationMemberMapper;
import com.lanchat.mapper.DeviceLoginMapper;
import com.lanchat.mapper.FileAccessGrantMapper;
import com.lanchat.mapper.FileMetadataMapper;
import com.lanchat.mapper.FriendRequestMapper;
import com.lanchat.mapper.FriendshipMapper;
import com.lanchat.mapper.GroupMemberMapper;
import com.lanchat.mapper.TemporaryRoomMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.security.JwtUtil;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.FileService;
import com.lanchat.service.BroadcastNotificationAccountService;
import com.lanchat.service.LoginAttemptService;
import com.lanchat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Set<String> ALLOWED_DEVICE_TYPES = Set.of("web", "desktop", "android", "ios");
    private static final String ARCHIVE_REASON = "ADMIN_ACCOUNT_ARCHIVE";

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
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private FriendshipMapper friendshipMapper;

    @Autowired
    private FriendRequestMapper friendRequestMapper;

    @Autowired
    private GroupMemberMapper groupMemberMapper;

    @Autowired
    private ConversationMemberMapper conversationMemberMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private FileAccessGrantMapper fileAccessGrantMapper;

    @Autowired
    private ChatGroupMapper chatGroupMapper;

    @Autowired
    private TemporaryRoomMapper temporaryRoomMapper;

    @Autowired
    private AdminUserLifecycleAuditMapper adminUserLifecycleAuditMapper;

    @Autowired
    private FileService fileService;

    @Autowired
    private AuthorizationService authorizationService;

    @Override
    @Transactional
    public boolean register(RegisterDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getUsername()) || !StringUtils.hasText(dto.getPassword())) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }

        String username = dto.getUsername().trim();
        if (username.length() < 3 || username.length() > 50
                || !username.matches("^[a-zA-Z0-9_.@-]+$")) {
            throw new IllegalArgumentException("用户名需为3-50位字母、数字或 ._@-");
        }
        if ("admin".equalsIgnoreCase(username)
                || BroadcastNotificationAccountService.USERNAME.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("该用户名为系统保留账号，不能通过注册接口创建");
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

        if (userMapper.insert(user) <= 0) return false;
        authorizationService.provisionMember(user.getId());
        return true;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
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

        if (BroadcastNotificationAccountService.isNotificationAccount(user)) {
            throw new IllegalArgumentException("系统通知账户不可登录");
        }

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

        // Lock and re-read before changing sessions so a concurrent password/status change wins.
        User lockedUser = lockUser(user.getId());
        if (lockedUser == null || !passwordEncoder.matches(dto.getPassword(), lockedUser.getPassword())) {
            loginAttemptService.recordFailedAttempt(dto.getUsername());
            int remaining = 5 - loginAttemptService.getFailedAttempts(dto.getUsername());
            if (remaining > 0) {
                throw new IllegalArgumentException("用户名或密码错误，剩余尝试次数：" + remaining);
            }
            return null;
        }
        if (!Integer.valueOf(1).equals(lockedUser.getStatus())) {
            throw new IllegalArgumentException("账号已被锁定，请联系管理员");
        }

        String requestedDeviceType = StringUtils.hasText(dto.getDeviceType())
                ? dto.getDeviceType().trim().toLowerCase() : "web";
        String deviceType = ALLOWED_DEVICE_TYPES.contains(requestedDeviceType) ? requestedDeviceType : "web";
        String deviceName = StringUtils.hasText(dto.getDeviceName())
                ? dto.getDeviceName().trim().substring(0, Math.min(dto.getDeviceName().trim().length(), 100))
                : deviceType;

        List<DeviceLogin> replacedDevices = activeByTypeForUpdate(lockedUser.getId(), deviceType);

        // 生成 Token
        String token = jwtUtil.generateToken(lockedUser.getId(), lockedUser.getUsername(), deviceType);
        String refreshToken = jwtUtil.generateRefreshToken(lockedUser.getId(), deviceType);

        List<Long> replacedDeviceIds = deactivateLockedDevices(replacedDevices);

        // 保存设备登录记录
        DeviceLogin deviceLogin = new DeviceLogin();
        deviceLogin.setUserId(lockedUser.getId());
        deviceLogin.setDeviceType(deviceType);
        deviceLogin.setDeviceName(deviceName);
        deviceLogin.setToken(token);
        deviceLogin.setRefreshToken(refreshToken);
        deviceLogin.setLoginTime(LocalDateTime.now());
        deviceLogin.setExpireTime(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshExpiration() / 1000));
        deviceLogin.setStatus(1);
        if (deviceLoginMapper.insert(deviceLogin) != 1) {
            throw new IllegalStateException("设备会话创建失败");
        }

        // 在线状态只由 WebSocket 连接维护，登录接口仅记录最后登录时间。
        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(User::getId, lockedUser.getId())
                .set(User::getLastLoginAt, LocalDateTime.now());
        userMapper.update(null, updateWrapper);

        loginAttemptService.clearAttempts(dto.getUsername());
        publishRevocations(lockedUser.getId(), replacedDeviceIds,
                "SESSION_REPLACED", "同类型设备已在其他位置登录");

        // 构建返回结果
        LoginVO vo = new LoginVO();
        vo.setUserId(lockedUser.getId());
        vo.setUsername(lockedUser.getUsername());
        vo.setNickname(lockedUser.getNickname());
        vo.setAvatar(lockedUser.getAvatar());
        vo.setToken(token);
        vo.setRefreshToken(refreshToken);
        vo.setExpiresIn(jwtUtil.getExpiration() / 1000);
        return vo;
    }

    @Override
    @Transactional
    public LoginVO refreshToken(TokenRefreshDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getRefreshToken())
                || !jwtUtil.isRefreshToken(dto.getRefreshToken())) {
            return null;
        }

        Long userId = jwtUtil.getUserIdFromToken(dto.getRefreshToken());
        User user = lockUser(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                || BroadcastNotificationAccountService.isNotificationAccount(user)) {
            return null;
        }

        String deviceType = jwtUtil.getDeviceTypeFromToken(dto.getRefreshToken());
        if (!ALLOWED_DEVICE_TYPES.contains(deviceType)) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        DeviceLogin device = activeByTypeForUpdate(userId, deviceType).stream()
                .filter(candidate -> Objects.equals(dto.getRefreshToken(), candidate.getRefreshToken()))
                .filter(candidate -> candidate.getExpireTime() != null && candidate.getExpireTime().isAfter(now))
                .findFirst()
                .orElse(null);
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
    @Transactional
    public void logoutDevice(Long userId, Long deviceId) {
        if (userId == null || deviceId == null || lockUser(userId) == null) return;
        List<DeviceLogin> devices = activeForUpdate(userId).stream()
                .filter(device -> Objects.equals(deviceId, device.getId()))
                .toList();
        List<Long> revokedIds = deactivateLockedDevices(devices);
        publishRevocations(userId, revokedIds,
                "DEVICE_REVOKED", "此设备已被退出登录");
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
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                || BroadcastNotificationAccountService.isNotificationAccount(user)) {
            return false;
        }

        return getActiveDevice(token, userId, deviceType) != null;
    }

    @Override
    public DeviceLogin getActiveDevice(String token, Long userId, String deviceType) {
        if (!StringUtils.hasText(token) || userId == null || !StringUtils.hasText(deviceType)) {
            return null;
        }
        User user = userMapper.selectById(userId);
        if (BroadcastNotificationAccountService.isNotificationAccount(user)) {
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
    @Transactional
    public void logoutByToken(Long userId, String token) {
        if (userId == null || !StringUtils.hasText(token) || lockUser(userId) == null) return;
        List<DeviceLogin> devices = activeForUpdate(userId).stream()
                .filter(device -> Objects.equals(token, device.getToken()))
                .toList();
        List<Long> revokedIds = deactivateLockedDevices(devices);
        publishRevocations(userId, revokedIds, "LOGOUT", "设备已退出登录");
    }

    @Override
    @Transactional
    public void logoutByRefreshToken(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) return;
        DeviceLogin discovered = deviceLoginMapper.selectOne(new LambdaQueryWrapper<DeviceLogin>()
                .eq(DeviceLogin::getRefreshToken, refreshToken)
                .eq(DeviceLogin::getStatus, 1)
                .last("LIMIT 1"));
        if (discovered == null || lockUser(discovered.getUserId()) == null) return;

        List<DeviceLogin> devices = activeForUpdate(discovered.getUserId()).stream()
                .filter(device -> Objects.equals(refreshToken, device.getRefreshToken()))
                .toList();
        List<Long> revokedIds = deactivateLockedDevices(devices);
        publishRevocations(discovered.getUserId(), revokedIds,
                "LOGOUT", "设备已退出登录");
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
    public boolean archiveUserByAdmin(Long userId, Long actorUserId) {
        requireLifecycleActor(actorUserId);
        User user = lockUser(userId);
        requireArchivableUser(user);
        if (user.getArchivedAt() != null) {
            return true;
        }

        List<DeviceLogin> activeDevices = activeForUpdate(userId);
        FileCleanupStats files = cleanupUnreferencedUploads(userId);
        int transferredGroups = transferOwnedGroups(userId);
        int transferredRooms = transferOwnedRooms(userId);
        removeOperationalRelationships(userId, false);

        LocalDateTime archivedAt = LocalDateTime.now();
        String anonymousUsername = "archived-user-" + userId + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String disabledPassword = passwordEncoder.encode(UUID.randomUUID().toString());
        if (userMapper.archiveAccount(
                userId,
                actorUserId,
                anonymousUsername,
                disabledPassword,
                ARCHIVE_REASON,
                archivedAt) != 1) {
            throw new IllegalStateException("账号归档失败");
        }
        authorizationService.synchronizeMemberStatus(userId, false);

        deviceLoginMapper.delete(new LambdaQueryWrapper<DeviceLogin>()
                .eq(DeviceLogin::getUserId, userId));
        loginAttemptService.clearAttempts(user.getUsername());
        recordLifecycleAudit(
                actorUserId,
                userId,
                "ARCHIVED",
                ARCHIVE_REASON,
                lifecycleDetail(files, transferredGroups, transferredRooms));
        publishRevocations(
                userId,
                deviceIds(activeDevices),
                "ACCOUNT_ARCHIVED",
                "账号已归档，请联系管理员");
        return true;
    }

    @Override
    @Transactional
    public boolean physicallyEraseUserByAdmin(Long userId,
                                              Long actorUserId,
                                              String confirmationPhrase,
                                              String reason) {
        requireLifecycleActor(actorUserId);
        String expectedPhrase = "ERASE USER " + userId;
        if (!Objects.equals(expectedPhrase, confirmationPhrase)) {
            throw new IllegalArgumentException("确认短语不匹配，应输入：" + expectedPhrase);
        }
        String auditReason = normalizeErasureReason(reason);

        User user = lockUser(userId);
        requireArchivableUser(user);
        if (user.getArchivedAt() == null) {
            throw new IllegalArgumentException("账号必须先归档，才能执行物理擦除");
        }

        List<DeviceLogin> activeDevices = activeForUpdate(userId);
        FileCleanupStats files = cleanupUnreferencedUploads(userId);
        int transferredGroups = transferOwnedGroups(userId);
        int transferredRooms = transferOwnedRooms(userId);
        removeOperationalRelationships(userId, true);
        deviceLoginMapper.delete(new LambdaQueryWrapper<DeviceLogin>()
                .eq(DeviceLogin::getUserId, userId));

        recordLifecycleAudit(
                actorUserId,
                userId,
                "PHYSICALLY_ERASED",
                auditReason,
                lifecycleDetail(files, transferredGroups, transferredRooms));
        if (userMapper.deleteById(userId) != 1) {
            throw new IllegalStateException("账号物理擦除失败");
        }

        loginAttemptService.clearAttempts(user.getUsername());
        publishRevocations(
                userId,
                deviceIds(activeDevices),
                "ACCOUNT_ERASED",
                "账号数据已被管理员擦除");
        return true;
    }

    @Override
    @Transactional
    public boolean resetPasswordByAdmin(Long userId, String newPassword) {
        if (userId == null) {
            throw new IllegalArgumentException("用户参数不能为空");
        }

        User user = lockUser(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        requireNotNotificationAccount(user);
        if (authorizationService.isOrganizationOwner(userId)) {
            throw new IllegalArgumentException("组织所有者请使用“修改密码”功能");
        }

        validateAccountPassword(newPassword);
        List<DeviceLogin> devices = activeForUpdate(userId);

        LambdaUpdateWrapper<User> userWrapper = new LambdaUpdateWrapper<>();
        userWrapper.eq(User::getId, userId)
                .set(User::getPassword, passwordEncoder.encode(newPassword));
        boolean updated = userMapper.update(null, userWrapper) > 0;
        if (!updated) return false;

        List<Long> revokedIds = deactivateLockedDevices(devices);
        loginAttemptService.clearAttempts(user.getUsername());
        publishRevocations(userId, revokedIds,
                "PASSWORD_RESET", "密码已重置，请重新登录");
        return true;
    }

    @Override
    @Transactional
    public boolean setStatusByAdmin(Long userId, Integer status) {
        if (userId == null || status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("用户状态只能为0或1");
        }

        User user = lockUser(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        requireNotNotificationAccount(user);
        if (authorizationService.isOrganizationOwner(userId)) {
            throw new IllegalArgumentException("不能封禁组织所有者账号");
        }
        if (user.getArchivedAt() != null && status == 1) {
            throw new IllegalArgumentException("已归档账号不能重新启用");
        }

        List<DeviceLogin> devices = status == 0 ? activeForUpdate(userId) : List.of();
        boolean needsUpdate = !Objects.equals(status, user.getStatus())
                || (status == 0 && !Integer.valueOf(0).equals(user.getOnline()));
        if (needsUpdate) {
            LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(User::getId, userId)
                    .set(User::getStatus, status);
            if (status == 0) wrapper.set(User::getOnline, 0);
            if (userMapper.update(null, wrapper) != 1) return false;
        }
        authorizationService.synchronizeMemberStatus(userId, status == 1);

        if (status == 0) {
            List<Long> revokedIds = deactivateLockedDevices(devices);
            publishRevocations(userId, revokedIds,
                    "ACCOUNT_DISABLED", "账号已被管理员停用");
        }
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
        requireNotNotificationAccount(target);
        if (authorizationService.isOrganizationOwner(userId)) {
            throw new IllegalArgumentException("组织所有者默认拥有广播权限，不能修改");
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
        requireNotNotificationAccount(user);

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
    @Transactional
    public boolean changePassword(ChangePasswordDTO dto) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId == null) {
            throw new IllegalArgumentException("用户未登录");
        }

        if (dto == null || !StringUtils.hasText(dto.getOldPassword())
                || !StringUtils.hasText(dto.getNewPassword())) {
            throw new IllegalArgumentException("密码参数不完整");
        }

        User user = lockUser(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        requireNotNotificationAccount(user);

        // 校验旧密码
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new IllegalArgumentException("原密码错误");
        }

        String newPassword = dto.getNewPassword();
        validateAccountPassword(newPassword);
        List<DeviceLogin> devices = activeForUpdate(userId);

        // 更新密码并撤销所有设备会话，避免旧令牌继续访问。
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
                .set(User::getPassword, passwordEncoder.encode(newPassword));
        boolean updated = userMapper.update(null, wrapper) > 0;
        if (updated) {
            List<Long> revokedIds = deactivateLockedDevices(devices);
            publishRevocations(userId, revokedIds,
                    "PASSWORD_CHANGED", "密码已修改，请重新登录");
        }
        return updated;
    }

    private FileCleanupStats cleanupUnreferencedUploads(Long userId) {
        List<FileMetadata> uploadedFiles = fileMetadataMapper.selectList(
                new LambdaQueryWrapper<FileMetadata>()
                        .eq(FileMetadata::getUploadUserId, userId)
                        .orderByAsc(FileMetadata::getId));
        if (uploadedFiles == null || uploadedFiles.isEmpty()) {
            return new FileCleanupStats(0, 0);
        }

        int cleaned = 0;
        int retained = 0;
        for (FileMetadata metadata : uploadedFiles) {
            if (metadata == null || metadata.getId() == null
                    || !StringUtils.hasText(metadata.getFilePath())) {
                retained++;
                continue;
            }
            int deleted = fileMetadataMapper.deleteUnreferencedUpload(
                    metadata.getId(), userId, metadata.getFilePath());
            if (deleted == 1) {
                fileAccessGrantMapper.delete(new LambdaQueryWrapper<FileAccessGrant>()
                        .eq(FileAccessGrant::getFileId, metadata.getId()));
                fileService.deleteStoredObjects(metadata);
                cleaned++;
            } else {
                retained++;
            }
        }
        return new FileCleanupStats(retained, cleaned);
    }

    private int transferOwnedGroups(Long userId) {
        List<ChatGroup> ownedGroups = chatGroupMapper.selectList(
                new LambdaQueryWrapper<ChatGroup>()
                        .eq(ChatGroup::getOwnerId, userId)
                        .orderByAsc(ChatGroup::getId));
        if (ownedGroups == null || ownedGroups.isEmpty()) return 0;

        int transferred = 0;
        for (ChatGroup group : ownedGroups) {
            if (group == null || group.getId() == null) continue;
            Long nextOwnerId = selectActiveGroupSuccessor(group.getId(), userId);
            // Never add the administrator to a private group merely because its owner was
            // archived. With no active successor, retain the anonymous owner id as a
            // history tombstone; physical erasure may later leave that non-authorizing id.
            if (nextOwnerId == null) continue;
            ensureGroupOwnerMembership(group.getId(), nextOwnerId);

            group.setOwnerId(nextOwnerId);
            group.setUpdateTime(LocalDateTime.now());
            if (chatGroupMapper.updateById(group) != 1) {
                throw new IllegalStateException("群组所有权转移失败");
            }
            conversationMemberMapper.insertIfAbsent(
                    ConversationIds.groupConversation(group.getId()),
                    nextOwnerId,
                    "OWNER");
            transferred++;
        }
        return transferred;
    }

    private Long selectActiveGroupSuccessor(Long groupId, Long archivedUserId) {
        List<GroupMember> candidates = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .ne(GroupMember::getUserId, archivedUserId)
                        .orderByDesc(GroupMember::getRole)
                        .orderByAsc(GroupMember::getJoinTime)
                        .orderByAsc(GroupMember::getId));
        if (candidates == null) return null;
        for (GroupMember candidate : candidates) {
            if (candidate == null || candidate.getUserId() == null) continue;
            User candidateUser = userMapper.selectById(candidate.getUserId());
            if (candidateUser != null
                    && Integer.valueOf(1).equals(candidateUser.getStatus())
                    && candidateUser.getArchivedAt() == null) {
                return candidate.getUserId();
            }
        }
        return null;
    }

    private void ensureGroupOwnerMembership(Long groupId, Long ownerId) {
        GroupMember membership = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, ownerId)
                        .last("LIMIT 1"));
        if (membership == null) {
            membership = new GroupMember();
            membership.setGroupId(groupId);
            membership.setUserId(ownerId);
            membership.setRole(2);
            membership.setJoinTime(LocalDateTime.now());
            if (groupMemberMapper.insert(membership) != 1) {
                throw new IllegalStateException("群组所有权转移失败");
            }
            return;
        }
        if (!Integer.valueOf(2).equals(membership.getRole())) {
            membership.setRole(2);
            if (groupMemberMapper.updateById(membership) != 1) {
                throw new IllegalStateException("群组所有权转移失败");
            }
        }
    }

    private int transferOwnedRooms(Long userId) {
        List<TemporaryRoom> rooms = temporaryRoomMapper.selectList(
                new LambdaQueryWrapper<TemporaryRoom>()
                        .eq(TemporaryRoom::getOwnerId, userId)
                        .orderByAsc(TemporaryRoom::getId));
        if (rooms == null || rooms.isEmpty()) return 0;

        int transferred = 0;
        for (TemporaryRoom room : rooms) {
            if (room == null || room.getId() == null) continue;
            Long nextOwnerId = selectActiveConversationSuccessor(
                    ConversationIds.temporaryConversation(room.getId()), userId);
            // As with groups, do not grant the administrator access to room history.
            if (nextOwnerId == null) continue;
            room.setOwnerId(nextOwnerId);
            room.setUpdateTime(LocalDateTime.now());
            if (temporaryRoomMapper.updateById(room) != 1) {
                throw new IllegalStateException("临时房间所有权转移失败");
            }
            conversationMemberMapper.insertIfAbsent(
                    ConversationIds.temporaryConversation(room.getId()),
                    nextOwnerId,
                    "OWNER");
            transferred++;
        }
        return transferred;
    }

    private Long selectActiveConversationSuccessor(String conversationId, Long archivedUserId) {
        List<ConversationMember> candidates = conversationMemberMapper.selectList(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .ne(ConversationMember::getUserId, archivedUserId)
                        .isNull(ConversationMember::getLeftTime)
                        .orderByAsc(ConversationMember::getId));
        if (candidates == null) return null;
        for (ConversationMember candidate : candidates) {
            if (candidate == null || candidate.getUserId() == null) continue;
            User candidateUser = userMapper.selectById(candidate.getUserId());
            if (candidateUser != null
                    && Integer.valueOf(1).equals(candidateUser.getStatus())
                    && candidateUser.getArchivedAt() == null) {
                return candidate.getUserId();
            }
        }
        return null;
    }

    private void removeOperationalRelationships(Long userId, boolean physicalErasure) {
        friendshipMapper.delete(new LambdaQueryWrapper<Friendship>()
                .eq(Friendship::getUserId, userId)
                .or()
                .eq(Friendship::getFriendId, userId));
        friendRequestMapper.delete(new LambdaQueryWrapper<FriendRequest>()
                .eq(FriendRequest::getFromUserId, userId)
                .or()
                .eq(FriendRequest::getToUserId, userId));
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getUserId, userId));
        fileAccessGrantMapper.delete(new LambdaQueryWrapper<FileAccessGrant>()
                .eq(FileAccessGrant::getUserId, userId));
        if (physicalErasure) {
            conversationMemberMapper.deleteByUserId(userId);
        } else {
            conversationMemberMapper.markUserLeft(userId);
        }
    }

    private void recordLifecycleAudit(Long actorUserId,
                                      Long targetUserId,
                                      String action,
                                      String reason,
                                      String detail) {
        AdminUserLifecycleAudit audit = new AdminUserLifecycleAudit();
        audit.setActorUserId(actorUserId);
        audit.setTargetUserId(targetUserId);
        audit.setAction(action);
        audit.setReason(reason);
        audit.setDetail(detail);
        audit.setCreateTime(LocalDateTime.now());
        if (adminUserLifecycleAuditMapper.insert(audit) != 1) {
            throw new IllegalStateException("账号生命周期审计写入失败");
        }
    }

    private String lifecycleDetail(FileCleanupStats files,
                                   int transferredGroups,
                                   int transferredRooms) {
        return "retainedFiles=" + files.retained()
                + ";cleanedFiles=" + files.cleaned()
                + ";transferredGroups=" + transferredGroups
                + ";transferredRooms=" + transferredRooms;
    }

    private void requireLifecycleActor(Long actorUserId) {
        if (actorUserId == null || actorUserId <= 0) {
            throw new IllegalArgumentException("管理员身份无效");
        }
    }

    private void requireArchivableUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        requireNotNotificationAccount(user);
        if (authorizationService.isOrganizationOwner(user.getId())) {
            throw new IllegalArgumentException("不能归档或擦除组织所有者账号");
        }
    }

    private void requireNotNotificationAccount(User user) {
        if (BroadcastNotificationAccountService.isNotificationAccount(user)) {
            throw new IllegalArgumentException("系统通知账户不能执行此操作");
        }
    }

    private String normalizeErasureReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("数据擦除原因不能为空");
        }
        String sanitized = reason.replaceAll("[\\p{Cntrl}]", "").trim();
        if (sanitized.length() < 4 || sanitized.length() > 500) {
            throw new IllegalArgumentException("数据擦除原因需为4-500个字符");
        }
        return sanitized;
    }

    private List<Long> deviceIds(List<DeviceLogin> devices) {
        if (devices == null) return List.of();
        return devices.stream()
                .map(DeviceLogin::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private User lockUser(Long userId) {
        if (userId == null || userMapper.lockById(userId) == null) return null;
        return userMapper.selectById(userId);
    }

    private List<DeviceLogin> activeByTypeForUpdate(Long userId, String deviceType) {
        List<DeviceLogin> devices = deviceLoginMapper.selectActiveByTypeForUpdate(userId, deviceType);
        return devices == null ? List.of() : devices;
    }

    private List<DeviceLogin> activeForUpdate(Long userId) {
        List<DeviceLogin> devices = deviceLoginMapper.selectActiveForUpdate(userId);
        return devices == null ? List.of() : devices;
    }

    private List<Long> deactivateLockedDevices(List<DeviceLogin> devices) {
        List<Long> deviceIds = devices == null ? List.of() : devices.stream()
                .map(DeviceLogin::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (deviceIds.isEmpty()) return List.of();

        LambdaUpdateWrapper<DeviceLogin> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(DeviceLogin::getId, deviceIds)
                .eq(DeviceLogin::getStatus, 1)
                .set(DeviceLogin::getStatus, 0);
        if (deviceLoginMapper.update(null, wrapper) != deviceIds.size()) {
            throw new IllegalStateException("设备会话撤销失败");
        }
        return deviceIds;
    }

    private void publishRevocations(Long userId,
                                    List<Long> deviceIds,
                                    String reason,
                                    String message) {
        if (userId == null || deviceIds == null || deviceIds.isEmpty()) return;
        applicationEventPublisher.publishEvent(
                new DeviceSessionsRevokedEvent(userId, deviceIds, reason, message));
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

    private record FileCleanupStats(int retained, int cleaned) {
    }
}
