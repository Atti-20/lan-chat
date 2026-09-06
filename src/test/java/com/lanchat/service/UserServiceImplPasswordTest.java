package com.lanchat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanchat.common.DeviceSessionsRevokedEvent;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.mapper.DeviceLoginMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplPasswordTest {

    private UserMapper userMapper;
    private DeviceLoginMapper deviceLoginMapper;
    private PasswordEncoder passwordEncoder;
    private LoginAttemptService loginAttemptService;
    private ApplicationEventPublisher eventPublisher;
    private UserServiceImpl service;
    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        initializeTableInfo(User.class);
        initializeTableInfo(DeviceLogin.class);
        userMapper = mock(UserMapper.class);
        deviceLoginMapper = mock(DeviceLoginMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        loginAttemptService = mock(LoginAttemptService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        authorizationService = mock(AuthorizationService.class);
        service = new UserServiceImpl();

        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "deviceLoginMapper", deviceLoginMapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "loginAttemptService", loginAttemptService);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", eventPublisher);
        ReflectionTestUtils.setField(service, "authorizationService", authorizationService);
    }

    @Test
    void administratorResetReplacesPasswordAndRevokesActiveSessions() {
        User target = user(7L, "alice");
        DeviceLogin desktop = device(31L);
        DeviceLogin web = device(32L);
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(target);
        when(deviceLoginMapper.selectActiveForUpdate(7L)).thenReturn(List.of(desktop, web));
        when(passwordEncoder.encode("Member5678")).thenReturn("encoded-password");
        when(userMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(deviceLoginMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(2);

        assertTrue(service.resetPasswordByAdmin(7L, "Member5678"));

        verify(passwordEncoder).encode("Member5678");
        verify(userMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(deviceLoginMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(loginAttemptService).clearAttempts("alice");
        ArgumentCaptor<DeviceSessionsRevokedEvent> revoked =
                ArgumentCaptor.forClass(DeviceSessionsRevokedEvent.class);
        verify(eventPublisher).publishEvent(revoked.capture());
        assertEquals(List.of(31L, 32L), revoked.getValue().deviceIds());
        assertEquals("PASSWORD_RESET", revoked.getValue().reason());

        InOrder order = inOrder(userMapper, deviceLoginMapper);
        order.verify(userMapper).lockById(7L);
        order.verify(deviceLoginMapper).selectActiveForUpdate(7L);
        order.verify(userMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        order.verify(deviceLoginMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void administratorCannotResetRootAccountWithoutCurrentPassword() {
        when(userMapper.lockById(1L)).thenReturn(1L);
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin"));
        when(authorizationService.isOrganizationOwner(1L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.resetPasswordByAdmin(1L, "Another1234"));

        verify(passwordEncoder, never()).encode(any());
        verify(deviceLoginMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void weakResetPasswordIsRejectedBeforeDatabaseUpdate() {
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice"));

        assertThrows(IllegalArgumentException.class,
                () -> service.resetPasswordByAdmin(7L, "password"));

        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void administratorDisableLocksThenRevokesEveryActiveDevice() {
        User target = user(7L, "alice");
        target.setStatus(1);
        target.setOnline(1);
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(target);
        when(deviceLoginMapper.selectActiveForUpdate(7L))
                .thenReturn(List.of(device(31L), device(32L)));
        when(userMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(deviceLoginMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(2);

        assertTrue(service.setStatusByAdmin(7L, 0));

        ArgumentCaptor<DeviceSessionsRevokedEvent> revoked =
                ArgumentCaptor.forClass(DeviceSessionsRevokedEvent.class);
        verify(eventPublisher).publishEvent(revoked.capture());
        assertEquals(List.of(31L, 32L), revoked.getValue().deviceIds());
        assertEquals("ACCOUNT_DISABLED", revoked.getValue().reason());

        InOrder order = inOrder(userMapper, deviceLoginMapper);
        order.verify(userMapper).lockById(7L);
        order.verify(deviceLoginMapper).selectActiveForUpdate(7L);
        order.verify(userMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        order.verify(deviceLoginMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void deviceLogoutRevokesOnlyTheRequestedLockedSession() {
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice"));
        when(deviceLoginMapper.selectActiveForUpdate(7L))
                .thenReturn(List.of(device(31L), device(32L)));
        when(deviceLoginMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.logoutDevice(7L, 32L);

        ArgumentCaptor<DeviceSessionsRevokedEvent> revoked =
                ArgumentCaptor.forClass(DeviceSessionsRevokedEvent.class);
        verify(eventPublisher).publishEvent(revoked.capture());
        assertEquals(List.of(32L), revoked.getValue().deviceIds());
        assertEquals("DEVICE_REVOKED", revoked.getValue().reason());

        InOrder order = inOrder(userMapper, deviceLoginMapper);
        order.verify(userMapper).lockById(7L);
        order.verify(deviceLoginMapper).selectActiveForUpdate(7L);
        order.verify(deviceLoginMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private DeviceLogin device(Long id) {
        DeviceLogin device = new DeviceLogin();
        device.setId(id);
        device.setUserId(7L);
        device.setStatus(1);
        return device;
    }

    private void initializeTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) != null) return;
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
