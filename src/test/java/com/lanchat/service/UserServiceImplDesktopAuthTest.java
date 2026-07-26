package com.lanchat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanchat.common.DeviceSessionsRevokedEvent;
import com.lanchat.dto.LoginDTO;
import com.lanchat.dto.TokenRefreshDTO;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.mapper.DeviceLoginMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.security.JwtUtil;
import com.lanchat.service.impl.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplDesktopAuthTest {

    private UserMapper userMapper;
    private DeviceLoginMapper deviceLoginMapper;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private LoginAttemptService loginAttemptService;
    private ApplicationEventPublisher eventPublisher;
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
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "deviceLoginMapper", deviceLoginMapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(service, "loginAttemptService", loginAttemptService);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", eventPublisher);
    }

    @Test
    void desktopLoginCreatesAndInvalidatesOnlyDesktopSessions() {
        User user = activeUser();
        DeviceLogin previous = activeDevice(30L, "old-refresh");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(passwordEncoder.matches("Member1234", "password-hash")).thenReturn(true);
        when(deviceLoginMapper.selectActiveByTypeForUpdate(7L, "desktop"))
                .thenReturn(List.of(previous));
        when(deviceLoginMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(deviceLoginMapper.insert(any(DeviceLogin.class))).thenReturn(1);
        when(userMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
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

        verify(deviceLoginMapper).selectActiveByTypeForUpdate(7L, "desktop");
        ArgumentCaptor<DeviceSessionsRevokedEvent> revoked =
                ArgumentCaptor.forClass(DeviceSessionsRevokedEvent.class);
        verify(eventPublisher).publishEvent(revoked.capture());
        assertEquals(List.of(30L), revoked.getValue().deviceIds());
        assertEquals("SESSION_REPLACED", revoked.getValue().reason());

        InOrder order = inOrder(userMapper, deviceLoginMapper);
        order.verify(userMapper).lockById(7L);
        order.verify(deviceLoginMapper).selectActiveByTypeForUpdate(7L, "desktop");
        order.verify(deviceLoginMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        order.verify(deviceLoginMapper).insert(any(DeviceLogin.class));
    }

    @Test
    void desktopRefreshRotatesTheDesktopDeviceSession() {
        User user = activeUser();
        DeviceLogin device = new DeviceLogin();
        device.setId(31L);
        device.setRefreshToken("old-refresh");
        device.setExpireTime(LocalDateTime.now().plusHours(1));
        device.setStatus(1);
        when(jwtUtil.isRefreshToken("old-refresh")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("old-refresh")).thenReturn(7L);
        when(jwtUtil.getDeviceTypeFromToken("old-refresh")).thenReturn("desktop");
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(deviceLoginMapper.selectActiveByTypeForUpdate(7L, "desktop"))
                .thenReturn(List.of(device));
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
        InOrder order = inOrder(userMapper, deviceLoginMapper);
        order.verify(userMapper).lockById(7L);
        order.verify(deviceLoginMapper).selectActiveByTypeForUpdate(7L, "desktop");
        order.verify(deviceLoginMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void failedSessionInsertAbortsTransactionalLoginBeforePublishingRevocation() throws Exception {
        User user = activeUser();
        DeviceLogin previous = activeDevice(30L, "old-refresh");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(passwordEncoder.matches("Member1234", "password-hash")).thenReturn(true);
        when(deviceLoginMapper.selectActiveByTypeForUpdate(7L, "desktop"))
                .thenReturn(List.of(previous));
        when(deviceLoginMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(deviceLoginMapper.insert(any(DeviceLogin.class))).thenReturn(0);
        when(jwtUtil.generateToken(7L, "alice", "desktop")).thenReturn("desktop-access");
        when(jwtUtil.generateRefreshToken(7L, "desktop")).thenReturn("desktop-refresh");
        when(jwtUtil.getRefreshExpiration()).thenReturn(604_800_000L);

        LoginDTO request = new LoginDTO();
        request.setUsername("alice");
        request.setPassword("Member1234");
        request.setDeviceType("desktop");

        assertThrows(IllegalStateException.class, () -> service.login(request));
        Transactional transaction = UserServiceImpl.class.getMethod("login", LoginDTO.class)
                .getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation());
        verify(userMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deviceSessionLookupUsesCurrentReadsAfterTheUserRowLock() throws Exception {
        String byTypeSql = selectSql(
                DeviceLoginMapper.class,
                "selectActiveByTypeForUpdate",
                Long.class,
                String.class);
        String allActiveSql = selectSql(
                DeviceLoginMapper.class,
                "selectActiveForUpdate",
                Long.class);
        String userLockSql = selectSql(UserMapper.class, "lockById", Long.class);

        assertTrue(byTypeSql.toUpperCase().contains("FOR UPDATE"), byTypeSql);
        assertTrue(allActiveSql.toUpperCase().contains("FOR UPDATE"), allActiveSql);
        assertTrue(byTypeSql.contains("user_id = #{userId}"), byTypeSql);
        assertTrue(byTypeSql.contains("active_device_type = #{deviceType}"), byTypeSql);
        assertTrue(allActiveSql.contains("active_device_type IS NOT NULL"), allActiveSql);
        assertTrue(userLockSql.toUpperCase().contains("FOR UPDATE"), userLockSql);
    }

    private User activeUser() {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setPassword("password-hash");
        user.setStatus(1);
        return user;
    }

    private DeviceLogin activeDevice(Long id, String refreshToken) {
        DeviceLogin device = new DeviceLogin();
        device.setId(id);
        device.setUserId(7L);
        device.setDeviceType("desktop");
        device.setToken("old-access");
        device.setRefreshToken(refreshToken);
        device.setExpireTime(LocalDateTime.now().plusHours(1));
        device.setStatus(1);
        return device;
    }

    private void initializeTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) != null) return;
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    private String selectSql(Class<?> mapperType,
                             String methodName,
                             Class<?>... parameterTypes) throws Exception {
        Select select = mapperType.getMethod(methodName, parameterTypes).getAnnotation(Select.class);
        assertNotNull(select);
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }
}
