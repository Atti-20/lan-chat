package com.lanchat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.mapper.DeviceLoginMapper;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplPasswordTest {

    private UserMapper userMapper;
    private DeviceLoginMapper deviceLoginMapper;
    private PasswordEncoder passwordEncoder;
    private LoginAttemptService loginAttemptService;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeTableInfo(User.class);
        initializeTableInfo(DeviceLogin.class);
        userMapper = mock(UserMapper.class);
        deviceLoginMapper = mock(DeviceLoginMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        loginAttemptService = mock(LoginAttemptService.class);
        service = new UserServiceImpl();

        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "deviceLoginMapper", deviceLoginMapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "loginAttemptService", loginAttemptService);
    }

    @Test
    void administratorResetReplacesPasswordAndRevokesActiveSessions() {
        User target = user(7L, "alice");
        when(userMapper.selectById(7L)).thenReturn(target);
        when(passwordEncoder.encode("Member5678")).thenReturn("encoded-password");
        when(userMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(deviceLoginMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(2);

        assertTrue(service.resetPasswordByAdmin(7L, "Member5678"));

        verify(passwordEncoder).encode("Member5678");
        verify(userMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(deviceLoginMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(loginAttemptService).clearAttempts("alice");
    }

    @Test
    void administratorCannotResetRootAccountWithoutCurrentPassword() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin"));

        assertThrows(IllegalArgumentException.class,
                () -> service.resetPasswordByAdmin(1L, "Another1234"));

        verify(passwordEncoder, never()).encode(any());
        verify(deviceLoginMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void weakResetPasswordIsRejectedBeforeDatabaseUpdate() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice"));

        assertThrows(IllegalArgumentException.class,
                () -> service.resetPasswordByAdmin(7L, "password"));

        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private void initializeTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) != null) return;
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
