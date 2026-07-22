package com.lanchat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanchat.dto.LoginDTO;
import com.lanchat.dto.TokenRefreshDTO;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.mapper.DeviceLoginMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.impl.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplDesktopAuthTest {

    private UserMapper userMapper;
    private DeviceLoginMapper deviceLoginMapper;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private LoginAttemptService loginAttemptService;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeTableInfo(User.class);
        initializeTableInfo(DeviceLogin.class);
        userMapper = mock(UserMapper.class);
        deviceLoginMapper = mock(DeviceLoginMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        loginAttemptService = mock(LoginAttemptService.class);
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "deviceLoginMapper", deviceLoginMapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(service, "loginAttemptService", loginAttemptService);
    }

    @Test
    void desktopLoginCreatesAndInvalidatesOnlyDesktopSessions() {
        User user = activeUser();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("Member1234", "password-hash")).thenReturn(true);
        when(jwtUtil.generateToken(7L, "alice", "desktop")).thenReturn("desktop-access");
        when(jwtUtil.generateRefreshToken(7L, "desktop")).thenReturn("desktop-refresh");
        when(jwtUtil.getRefreshExpiration()).thenReturn(604_800_000L);

        LoginDTO request = new LoginDTO();
        request.setUsername("alice");
        request.setPassword("Member1234");
        request.setDeviceType("desktop");
        request.setDeviceName("LANChat macOS");

        var result = service.login(request);

        assertNotNull(result);
        assertEquals("desktop-access", result.getToken());
        ArgumentCaptor<DeviceLogin> inserted = ArgumentCaptor.forClass(DeviceLogin.class);
        verify(deviceLoginMapper).insert(inserted.capture());
        assertEquals("desktop", inserted.getValue().getDeviceType());
        assertEquals("LANChat macOS", inserted.getValue().getDeviceName());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<DeviceLogin>> invalidation =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deviceLoginMapper).update(isNull(), invalidation.capture());
        String sqlSegment = invalidation.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("device_type") || sqlSegment.contains("deviceType"), sqlSegment);
        assertTrue(invalidation.getValue().getParamNameValuePairs().containsValue("desktop"));
    }

    @Test
    void desktopRefreshRotatesTheDesktopDeviceSession() {
        User user = activeUser();
        DeviceLogin device = new DeviceLogin();
        device.setId(31L);
        when(jwtUtil.isRefreshToken("old-refresh")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("old-refresh")).thenReturn(7L);
        when(jwtUtil.getDeviceTypeFromToken("old-refresh")).thenReturn("desktop");
        when(userMapper.selectById(7L)).thenReturn(user);
        when(deviceLoginMapper.selectOne(any())).thenReturn(device);
        when(deviceLoginMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(jwtUtil.generateToken(7L, "alice", "desktop")).thenReturn("new-access");
        when(jwtUtil.generateRefreshToken(7L, "desktop")).thenReturn("new-refresh");
        when(jwtUtil.getExpiration()).thenReturn(7_200_000L);
        when(jwtUtil.getRefreshExpiration()).thenReturn(604_800_000L);

        TokenRefreshDTO request = new TokenRefreshDTO();
        request.setRefreshToken("old-refresh");
        request.setDeviceName("LANChat Windows");

        var result = service.refreshToken(request);

        assertNotNull(result);
        assertEquals("new-access", result.getToken());
        assertEquals("new-refresh", result.getRefreshToken());
        verify(jwtUtil).generateToken(7L, "alice", "desktop");
        verify(jwtUtil).generateRefreshToken(7L, "desktop");
    }

    private User activeUser() {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setPassword("password-hash");
        user.setStatus(1);
        return user;
    }

    private void initializeTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) != null) return;
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
