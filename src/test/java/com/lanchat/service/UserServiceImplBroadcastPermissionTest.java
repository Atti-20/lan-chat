package com.lanchat.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.entity.User;
import com.lanchat.mapper.UserMapper;
import com.lanchat.service.impl.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplBroadcastPermissionTest {

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeTableInfo(User.class);
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
    }

    @Test
    void administratorCanGrantPermissionToRegularAccount() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        assertTrue(service.setBroadcastPermission(7L, true));

        ArgumentCaptor<User> update = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(update.capture());
        assertEquals(7L, update.getValue().getId());
        assertEquals(1, update.getValue().getCanSendBroadcast());
    }

    @Test
    void administratorCanRevokePermissionFromRegularAccount() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        assertTrue(service.setBroadcastPermission(7L, false));

        ArgumentCaptor<User> update = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(update.capture());
        assertEquals(0, update.getValue().getCanSendBroadcast());
    }

    @Test
    void rootAdministratorPermissionCannotBeChanged() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin"));

        assertThrows(IllegalArgumentException.class,
                () -> service.setBroadcastPermission(1L, false));

        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void missingTargetAccountIsRejected() {
        when(userMapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.setBroadcastPermission(99L, true));

        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void newRegularAccountDefaultsToNoBroadcastPermission() {
        RegisterDTO request = new RegisterDTO();
        request.setUsername("new.member");
        request.setNickname("新成员");
        request.setPassword("Member1234");
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("Member1234")).thenReturn("encoded");
        when(userMapper.insert(any(User.class))).thenReturn(1);

        assertTrue(service.register(request));

        ArgumentCaptor<User> inserted = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(inserted.capture());
        assertEquals(0, inserted.getValue().getCanSendBroadcast());
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
