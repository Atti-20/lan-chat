package com.lanchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lanchat.dto.LoginDTO;
import com.lanchat.dto.LoginVO;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.dto.TokenRefreshDTO;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.mapper.DeviceLoginMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.LoginAttemptService;
import com.lanchat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

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

    @Override
    public boolean register(RegisterDTO dto) {
        if (!StringUtils.hasText(dto.getUsername()) || !StringUtils.hasText(dto.getPassword())) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }

        // 密码强度校验：8-20位，含字母和数字
        String password = dto.getPassword();
        if (password.length() < 8 || password.length() > 20) {
            throw new IllegalArgumentException("密码长度需为8-20位");
        }
        if (!password.matches(".*[a-zA-Z]+.*") || !password.matches(".*\\d+.*")) {
            throw new IllegalArgumentException("密码必须包含字母和数字");
        }

        // 昵称长度校验：2-16字符
        if (StringUtils.hasText(dto.getNickname())) {
            if (dto.getNickname().length() < 2 || dto.getNickname().length() > 16) {
                throw new IllegalArgumentException("昵称长度需为2-16字符");
            }
        }

        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        if (userMapper.selectCount(wrapper) > 0) {
            return false;
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setAvatar("");
        user.setSignature("");
        user.setOnline(0);
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());

        return userMapper.insert(user) > 0;
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        // 检查是否被锁定
        if (loginAttemptService.isLocked(dto.getUsername())) {
            long remaining = loginAttemptService.getRemainingLockTime(dto.getUsername());
            throw new RuntimeException("账号已被锁定，请" + remaining + "分钟后重试或找回密码");
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
                throw new RuntimeException("用户名或密码错误，剩余尝试次数：" + remaining);
            }
            return null;
        }

        // 检查账号状态
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new RuntimeException("账号已被锁定，请联系管理员");
        }

        // 登录成功，清除失败计数
        loginAttemptService.clearAttempts(dto.getUsername());

        String deviceType = StringUtils.hasText(dto.getDeviceType()) ? dto.getDeviceType() : "web";
        String deviceName = StringUtils.hasText(dto.getDeviceName()) ? dto.getDeviceName() : deviceType;

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

        // 更新在线状态和最后登录时间
        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(User::getId, user.getId())
                .set(User::getOnline, 1)
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
        if (!jwtUtil.validateToken(dto.getRefreshToken()) || !jwtUtil.isRefreshToken(dto.getRefreshToken())) {
            return null;
        }

        Long userId = jwtUtil.getUserIdFromToken(dto.getRefreshToken());
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }

        String deviceType = StringUtils.hasText(dto.getDeviceType()) ? dto.getDeviceType() : "web";
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), deviceType);
        String newRefreshToken = jwtUtil.generateRefreshToken(user.getId(), deviceType);

        // 更新设备登录记录中的 Token
        LambdaUpdateWrapper<DeviceLogin> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(DeviceLogin::getUserId, userId)
                .eq(DeviceLogin::getDeviceType, deviceType)
                .eq(DeviceLogin::getStatus, 1)
                .set(DeviceLogin::getToken, token)
                .set(DeviceLogin::getRefreshToken, newRefreshToken)
                .set(DeviceLogin::getExpireTime, LocalDateTime.now().plusSeconds(jwtUtil.getRefreshExpiration() / 1000));
        deviceLoginMapper.update(null, updateWrapper);

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
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(User::getUsername, keyword)
                .or()
                .like(User::getNickname, keyword)
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
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
                .set(User::getMuteStart, muteStart)
                .set(User::getMuteEnd, muteEnd);
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
}
