package com.lanchat.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.entity.User;
import com.lanchat.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BroadcastNotificationAccountServiceTest {

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private AuthorizationService authorizationService;
    private BroadcastNotificationAccountService service;

    @BeforeEach
    void setUp() {
        initializeTableInfo(User.class);
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authorizationService = mock(AuthorizationService.class);
        service = new BroadcastNotificationAccountService(
                userMapper, passwordEncoder, authorizationService);
    }

    @Test
    void createsReservedNonInteractiveAccountAndProvisionsIt() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("generated-password-hash");
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User account = invocation.getArgument(0);
            account.setId(81L);
            return 1;
        });

        User account = service.ensureAccount();

        assertEquals(81L, account.getId());
        assertEquals(BroadcastNotificationAccountService.USERNAME, account.getUsername());
        assertEquals(BroadcastNotificationAccountService.NICKNAME, account.getNickname());
        assertEquals(BroadcastNotificationAccountService.SIGNATURE, account.getSignature());
        assertEquals(BroadcastNotificationAccountService.AVATAR, account.getAvatar());
        assertEquals(1, account.getStatus());
        assertEquals(0, account.getCanSendBroadcast());
        assertTrue(BroadcastNotificationAccountService.isNotificationAccount(account));
        verify(passwordEncoder).encode(anyString());
        verify(authorizationService).provisionMember(81L);
    }

    @Test
    void repairsAStaleTechnicalAccountWithoutChangingItsPassword() {
        User existing = notificationAccount(82L);
        existing.setStatus(0);
        existing.setNickname("旧昵称");
        existing.setAvatar("old-avatar");
        existing.setSignature("旧签名");
        existing.setCanSendBroadcast(1);
        // It remains the technical account because the reserved signature is
        // restored below only after this compatibility check.
        existing.setSignature(BroadcastNotificationAccountService.SIGNATURE);
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        User account = service.ensureAccount();

        assertSame(existing, account);
        ArgumentCaptor<User> update = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(update.capture());
        assertEquals(1, update.getValue().getStatus());
        assertEquals(BroadcastNotificationAccountService.NICKNAME, update.getValue().getNickname());
        assertEquals(BroadcastNotificationAccountService.AVATAR, update.getValue().getAvatar());
        assertEquals(BroadcastNotificationAccountService.SIGNATURE, update.getValue().getSignature());
        assertEquals(0, update.getValue().getCanSendBroadcast());
        verify(passwordEncoder, never()).encode(anyString());
        verify(authorizationService).provisionMember(82L);
    }

    @Test
    void refusesToTakeOverARegularAccountThatClaimedTheReservedUsername() {
        User ordinaryUser = notificationAccount(83L);
        ordinaryUser.setSignature("普通用户签名");
        when(userMapper.selectOne(any())).thenReturn(ordinaryUser);

        assertThrows(IllegalStateException.class, () -> service.ensureAccount());

        verify(userMapper, never()).updateById(any(User.class));
        verify(authorizationService, never()).provisionMember(any());
    }

    @Test
    void resolvesAConcurrentReservedAccountCreationToTheVerifiedTechnicalAccount() {
        User concurrent = notificationAccount(84L);
        when(userMapper.selectOne(any())).thenReturn(null, concurrent);
        when(userMapper.insert(any(User.class))).thenThrow(new DuplicateKeyException("duplicate username"));

        User account = service.ensureAccount();

        assertSame(concurrent, account);
        verify(authorizationService).provisionMember(84L);
    }

    private User notificationAccount(Long id) {
        User account = new User();
        account.setId(id);
        account.setUsername(BroadcastNotificationAccountService.USERNAME);
        account.setSignature(BroadcastNotificationAccountService.SIGNATURE);
        return account;
    }

    private void initializeTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) != null) return;
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
