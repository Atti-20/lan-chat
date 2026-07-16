package com.lanchat.config;

import com.lanchat.entity.User;
import com.lanchat.mapper.DeviceLoginMapper;
import com.lanchat.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateDeploymentInitializerTest {

    private static final String VALID_EXISTING_HASH = "$2a$10$" + "a".repeat(53);
    private static final String HISTORICAL_DEMO_HASH =
            "$2a$10$VWOWjY7EBONKq/JPLNs/oO69k7SM4xG2qMskNP5MIH55T6ZwciU.C";

    @TempDir
    Path storage;

    private LanChatPrivateDeploymentProperties properties;
    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private DeviceLoginMapper deviceLoginMapper;
    private PrivateDeploymentInitializer initializer;

    @BeforeEach
    void setUp() {
        properties = new LanChatPrivateDeploymentProperties();
        properties.setEnabled(true);
        properties.setSelfRegistrationEnabled(false);
        properties.setBootstrapAdminNickname("节点管理员");

        LanChatNodeProperties nodeProperties = new LanChatNodeProperties();
        nodeProperties.setId("test-private-node");
        nodeProperties.setMode("LOCAL_INDEPENDENT");
        userMapper = mock(UserMapper.class);
        deviceLoginMapper = mock(DeviceLoginMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(userMapper.selectList(any())).thenReturn(List.of());
        initializer = new PrivateDeploymentInitializer(
                properties, nodeProperties, userMapper, deviceLoginMapper, passwordEncoder);

        ReflectionTestUtils.setField(initializer, "jwtSecret",
                "N8u2-xA7_qP4-zT9-kL6-vC3-mR5-hJ1-sW0-yF8");
        ReflectionTestUtils.setField(initializer, "databasePassword", "Db_7xP9mQ2vL5!");
        ReflectionTestUtils.setField(initializer, "redisPassword", "Redis_4nZ8tK6p!");
        ReflectionTestUtils.setField(initializer, "filePath", storage.toString());
        ReflectionTestUtils.setField(initializer, "tunnelEnabled", false);
        ReflectionTestUtils.setField(initializer, "allowedWebSocketOrigins", "http://localhost:8080");
    }

    @Test
    void createsAdministratorOnceWhenPrivateDatabaseIsNew() {
        properties.setBootstrapAdminPassword("Admin_9xQ2vL7!Aa");
        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode("Admin_9xQ2vL7!Aa")).thenReturn("bcrypt-hash");
        when(userMapper.insert(any(User.class))).thenReturn(1);

        initializer.run(null);

        ArgumentCaptor<User> inserted = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(inserted.capture());
        assertEquals("admin", inserted.getValue().getUsername());
        assertEquals("bcrypt-hash", inserted.getValue().getPassword());
        assertEquals("节点管理员", inserted.getValue().getNickname());
    }

    @Test
    void acceptsAdministratorCreatedConcurrentlyByAnotherInstance() {
        properties.setBootstrapAdminPassword("Admin_9xQ2vL7!Aa");
        User concurrentAdministrator = new User();
        concurrentAdministrator.setId(1L);
        concurrentAdministrator.setUsername("admin");
        concurrentAdministrator.setPassword(VALID_EXISTING_HASH);
        when(userMapper.selectOne(any())).thenReturn(null, concurrentAdministrator);
        when(passwordEncoder.encode("Admin_9xQ2vL7!Aa")).thenReturn(VALID_EXISTING_HASH);
        when(userMapper.insert(any(User.class)))
                .thenThrow(new DuplicateKeyException("duplicate admin"));

        assertDoesNotThrow(() -> initializer.run(null));

        verify(userMapper, times(2)).selectOne(any());
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void doesNotReapplyBootstrapPasswordToExistingAdministrator() {
        User existing = new User();
        existing.setId(1L);
        existing.setPassword(VALID_EXISTING_HASH);
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(passwordEncoder.matches("LanChat123!", VALID_EXISTING_HASH)).thenReturn(false);

        initializer.run(null);

        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void rotatesHistoricalDemoAdministratorPasswordAndRevokesSessions() {
        properties.setBootstrapAdminPassword("Admin_9xQ2vL7!Aa");
        User existing = new User();
        existing.setId(1L);
        existing.setPassword(HISTORICAL_DEMO_HASH);
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(passwordEncoder.matches("LanChat123!", HISTORICAL_DEMO_HASH)).thenReturn(true);
        when(passwordEncoder.encode("Admin_9xQ2vL7!Aa")).thenReturn("replacement-bcrypt-hash");
        when(userMapper.update(isNull(), any())).thenReturn(1);

        initializer.run(null);

        verify(userMapper).update(isNull(), any());
        verify(deviceLoginMapper).update(isNull(), any());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void rejectsDevelopmentJwtSecretInPrivateMode() {
        ReflectionTestUtils.setField(initializer, "jwtSecret",
                "lan-chat-local-development-secret-change-in-production-2026");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> initializer.run(null));

        assertTrue(failure.getMessage().contains("开发默认值"));
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void requiresStrongBootstrapPasswordForFirstAdministrator() {
        properties.setBootstrapAdminPassword("weak-secret12");
        when(userMapper.selectOne(any())).thenReturn(null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> initializer.run(null));

        assertTrue(failure.getMessage().contains("大写字母"));
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void rejectsOpenSelfRegistrationInPrivateMode() {
        properties.setSelfRegistrationEnabled(true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> initializer.run(null));

        assertTrue(failure.getMessage().contains("自助注册"));
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void disablesHistoricalRegularDemoAccounts() {
        User existingAdmin = new User();
        existingAdmin.setId(1L);
        existingAdmin.setPassword(VALID_EXISTING_HASH);
        User demoMember = new User();
        demoMember.setId(2L);
        demoMember.setUsername("alice");
        when(userMapper.selectOne(any())).thenReturn(existingAdmin);
        when(userMapper.selectList(any())).thenReturn(List.of(demoMember));
        when(userMapper.update(isNull(), any())).thenReturn(1);

        initializer.run(null);

        verify(userMapper).update(isNull(), any());
        verify(deviceLoginMapper).update(isNull(), any());
    }
}
