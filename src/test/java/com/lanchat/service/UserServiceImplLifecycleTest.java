package com.lanchat.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanchat.common.DeviceSessionsRevokedEvent;
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
import com.lanchat.service.impl.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplLifecycleTest {

    private UserMapper userMapper;
    private DeviceLoginMapper deviceLoginMapper;
    private PasswordEncoder passwordEncoder;
    private LoginAttemptService loginAttemptService;
    private ApplicationEventPublisher eventPublisher;
    private FriendshipMapper friendshipMapper;
    private FriendRequestMapper friendRequestMapper;
    private GroupMemberMapper groupMemberMapper;
    private ConversationMemberMapper conversationMemberMapper;
    private FileMetadataMapper fileMetadataMapper;
    private FileAccessGrantMapper fileAccessGrantMapper;
    private ChatGroupMapper chatGroupMapper;
    private TemporaryRoomMapper temporaryRoomMapper;
    private AdminUserLifecycleAuditMapper auditMapper;
    private FileService fileService;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        initializeTableInfo(User.class);
        initializeTableInfo(DeviceLogin.class);
        initializeTableInfo(Friendship.class);
        initializeTableInfo(FriendRequest.class);
        initializeTableInfo(GroupMember.class);
        initializeTableInfo(ConversationMember.class);
        initializeTableInfo(FileMetadata.class);
        initializeTableInfo(FileAccessGrant.class);
        initializeTableInfo(ChatGroup.class);
        initializeTableInfo(TemporaryRoom.class);

        userMapper = mock(UserMapper.class);
        deviceLoginMapper = mock(DeviceLoginMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        loginAttemptService = mock(LoginAttemptService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        friendshipMapper = mock(FriendshipMapper.class);
        friendRequestMapper = mock(FriendRequestMapper.class);
        groupMemberMapper = mock(GroupMemberMapper.class);
        conversationMemberMapper = mock(ConversationMemberMapper.class);
        fileMetadataMapper = mock(FileMetadataMapper.class);
        fileAccessGrantMapper = mock(FileAccessGrantMapper.class);
        chatGroupMapper = mock(ChatGroupMapper.class);
        temporaryRoomMapper = mock(TemporaryRoomMapper.class);
        auditMapper = mock(AdminUserLifecycleAuditMapper.class);
        fileService = mock(FileService.class);
        service = new UserServiceImpl();

        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "deviceLoginMapper", deviceLoginMapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "loginAttemptService", loginAttemptService);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", eventPublisher);
        ReflectionTestUtils.setField(service, "friendshipMapper", friendshipMapper);
        ReflectionTestUtils.setField(service, "friendRequestMapper", friendRequestMapper);
        ReflectionTestUtils.setField(service, "groupMemberMapper", groupMemberMapper);
        ReflectionTestUtils.setField(service, "conversationMemberMapper", conversationMemberMapper);
        ReflectionTestUtils.setField(service, "fileMetadataMapper", fileMetadataMapper);
        ReflectionTestUtils.setField(service, "fileAccessGrantMapper", fileAccessGrantMapper);
        ReflectionTestUtils.setField(service, "chatGroupMapper", chatGroupMapper);
        ReflectionTestUtils.setField(service, "temporaryRoomMapper", temporaryRoomMapper);
        ReflectionTestUtils.setField(service, "adminUserLifecycleAuditMapper", auditMapper);
        ReflectionTestUtils.setField(service, "fileService", fileService);

        when(fileMetadataMapper.selectList(any())).thenReturn(List.of());
        when(chatGroupMapper.selectList(any())).thenReturn(List.of());
        when(temporaryRoomMapper.selectList(any())).thenReturn(List.of());
        when(passwordEncoder.encode(anyString())).thenReturn("disabled-password-hash");
        when(auditMapper.insert(any(AdminUserLifecycleAudit.class))).thenReturn(1);
    }

    @Test
    void defaultRemovalArchivesProfileRevokesSessionsAndPreservesHistoryTables() {
        User target = user(7L, "alice");
        target.setStatus(1);
        target.setOnline(1);
        DeviceLogin desktop = device(31L);
        DeviceLogin web = device(32L);
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(target);
        when(deviceLoginMapper.selectActiveForUpdate(7L)).thenReturn(List.of(desktop, web));
        when(userMapper.archiveAccount(
                eq(7L), eq(1L), anyString(), eq("disabled-password-hash"),
                eq("ADMIN_ACCOUNT_ARCHIVE"), any(LocalDateTime.class))).thenReturn(1);

        assertTrue(service.archiveUserByAdmin(7L, 1L));

        verify(userMapper).archiveAccount(
                eq(7L),
                eq(1L),
                startsWith("archived-user-7-"),
                eq("disabled-password-hash"),
                eq("ADMIN_ACCOUNT_ARCHIVE"),
                any(LocalDateTime.class));
        verify(userMapper, never()).deleteById(7L);
        verify(conversationMemberMapper).markUserLeft(7L);
        verify(conversationMemberMapper, never()).deleteByUserId(7L);
        verify(loginAttemptService).clearAttempts("alice");

        ArgumentCaptor<AdminUserLifecycleAudit> audit =
                ArgumentCaptor.forClass(AdminUserLifecycleAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("ARCHIVED", audit.getValue().getAction());
        assertEquals(1L, audit.getValue().getActorUserId());
        assertEquals(7L, audit.getValue().getTargetUserId());
        assertEquals("retainedFiles=0;cleanedFiles=0;transferredGroups=0;transferredRooms=0",
                audit.getValue().getDetail());

        ArgumentCaptor<DeviceSessionsRevokedEvent> revoked =
                ArgumentCaptor.forClass(DeviceSessionsRevokedEvent.class);
        verify(eventPublisher).publishEvent(revoked.capture());
        assertEquals(List.of(31L, 32L), revoked.getValue().deviceIds());
        assertEquals("ACCOUNT_ARCHIVED", revoked.getValue().reason());
    }

    @Test
    void archiveCleansOnlyUnreferencedUploads() {
        User target = user(7L, "alice");
        FileMetadata referenced = file(51L, "referenced.pdf");
        FileMetadata orphan = file(52L, "orphan.pdf");
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(target);
        when(deviceLoginMapper.selectActiveForUpdate(7L)).thenReturn(List.of());
        when(fileMetadataMapper.selectList(any())).thenReturn(List.of(referenced, orphan));
        when(fileMetadataMapper.deleteUnreferencedUpload(51L, 7L, "referenced.pdf")).thenReturn(0);
        when(fileMetadataMapper.deleteUnreferencedUpload(52L, 7L, "orphan.pdf")).thenReturn(1);
        when(userMapper.archiveAccount(
                eq(7L), eq(1L), anyString(), anyString(),
                eq("ADMIN_ACCOUNT_ARCHIVE"), any(LocalDateTime.class))).thenReturn(1);

        assertTrue(service.archiveUserByAdmin(7L, 1L));

        verify(fileService, never()).deleteStoredObjects(referenced);
        verify(fileService).deleteStoredObjects(orphan);
        ArgumentCaptor<AdminUserLifecycleAudit> audit =
                ArgumentCaptor.forClass(AdminUserLifecycleAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("retainedFiles=1;cleanedFiles=1;transferredGroups=0;transferredRooms=0",
                audit.getValue().getDetail());
    }

    @Test
    void archiveTransfersOwnedGroupInsteadOfDeletingIt() {
        User target = user(7L, "alice");
        User successor = user(8L, "bob");
        successor.setStatus(1);
        ChatGroup group = new ChatGroup();
        group.setId(41L);
        group.setOwnerId(7L);
        GroupMember member = new GroupMember();
        member.setId(81L);
        member.setGroupId(41L);
        member.setUserId(8L);
        member.setRole(1);

        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(target);
        when(userMapper.selectById(8L)).thenReturn(successor);
        when(deviceLoginMapper.selectActiveForUpdate(7L)).thenReturn(List.of());
        when(chatGroupMapper.selectList(any())).thenReturn(List.of(group));
        when(groupMemberMapper.selectList(any())).thenReturn(List.of(member));
        when(groupMemberMapper.selectOne(any())).thenReturn(member);
        when(groupMemberMapper.updateById(member)).thenReturn(1);
        when(chatGroupMapper.updateById(group)).thenReturn(1);
        when(conversationMemberMapper.insertIfAbsent("group:41", 8L, "OWNER")).thenReturn(1);
        when(userMapper.archiveAccount(
                eq(7L), eq(1L), anyString(), anyString(),
                eq("ADMIN_ACCOUNT_ARCHIVE"), any(LocalDateTime.class))).thenReturn(1);

        assertTrue(service.archiveUserByAdmin(7L, 1L));

        assertEquals(8L, group.getOwnerId());
        assertEquals(2, member.getRole());
        verify(chatGroupMapper).updateById(group);
        verify(chatGroupMapper, never()).delete(any());
        verify(conversationMemberMapper).insertIfAbsent("group:41", 8L, "OWNER");
    }

    @Test
    void physicalErasureRequiresAnAlreadyArchivedAccount() {
        User target = user(7L, "alice");
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(target);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.physicallyEraseUserByAdmin(
                        7L, 1L, "ERASE USER 7", "合规数据擦除"));

        assertTrue(error.getMessage().contains("必须先归档"));
        verify(userMapper, never()).deleteById(7L);
        verify(auditMapper, never()).insert(any(AdminUserLifecycleAudit.class));
    }

    @Test
    void physicalErasureRequiresExactPhraseAndBoundedReason() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.physicallyEraseUserByAdmin(
                        7L, 1L, "erase user 7", "合规数据擦除"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.physicallyEraseUserByAdmin(
                        7L, 1L, "ERASE USER 7", "x"));

        verify(userMapper, never()).lockById(any());
        verify(userMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void physicalErasureDeletesOnlyIdentityAndOperationalRelationships() {
        User archived = user(7L, "archived-user-7-deadbeef");
        archived.setStatus(0);
        archived.setArchivedAt(LocalDateTime.now().minusDays(1));
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(archived);
        when(deviceLoginMapper.selectActiveForUpdate(7L)).thenReturn(List.of());
        when(userMapper.deleteById(7L)).thenReturn(1);

        assertTrue(service.physicallyEraseUserByAdmin(
                7L, 1L, "ERASE USER 7", "用户书面请求数据擦除"));

        verify(conversationMemberMapper).deleteByUserId(7L);
        verify(conversationMemberMapper, never()).markUserLeft(7L);
        verify(userMapper).deleteById(7L);
        verify(chatGroupMapper, never()).delete(any());

        ArgumentCaptor<AdminUserLifecycleAudit> audit =
                ArgumentCaptor.forClass(AdminUserLifecycleAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("PHYSICALLY_ERASED", audit.getValue().getAction());
        assertEquals("用户书面请求数据擦除", audit.getValue().getReason());
    }

    @Test
    void rootAdministratorCanNeverBeArchived() {
        when(userMapper.lockById(1L)).thenReturn(1L);
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin"));

        assertThrows(IllegalArgumentException.class,
                () -> service.archiveUserByAdmin(1L, 1L));

        verify(userMapper, never()).archiveAccount(
                any(), any(), anyString(), anyString(), anyString(), any());
        verify(auditMapper, never()).insert(any(AdminUserLifecycleAudit.class));
    }

    @Test
    void archiveMapperContractClearsEverySensitiveProfileField() throws Exception {
        Update update = UserMapper.class.getMethod(
                        "archiveAccount",
                        Long.class,
                        Long.class,
                        String.class,
                        String.class,
                        String.class,
                        LocalDateTime.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ");

        assertTrue(sql.contains("username = #{anonymousUsername}"));
        assertTrue(sql.contains("password = #{disabledPassword}"));
        assertTrue(sql.contains("nickname = '已注销用户'"));
        assertTrue(sql.contains("avatar = ''"));
        assertTrue(sql.contains("signature = ''"));
        assertTrue(sql.contains("last_login_at = NULL"));
        assertTrue(sql.contains("status = 0"));
        assertTrue(sql.contains("mute_start = NULL"));
        assertTrue(sql.contains("mute_end = NULL"));
        assertTrue(sql.contains("archived_at IS NULL"));
    }

    @Test
    void fileCleanupMapperKeepsSharedGrantsAndAvatarReferences() throws Exception {
        Delete delete = FileMetadataMapper.class.getMethod(
                        "deleteUnreferencedUpload",
                        Long.class,
                        Long.class,
                        String.class)
                .getAnnotation(Delete.class);
        String sql = String.join(" ", delete.value()).replaceAll("\\s+", " ");

        assertTrue(sql.contains("FROM file_access_grant"));
        assertTrue(sql.contains("user_id <> #{userId}"));
        assertTrue(sql.contains("FROM `user`"));
        assertTrue(sql.contains("id <> #{userId}"));
        assertTrue(sql.contains("FROM chat_group"));
        assertTrue(sql.contains("CONCAT('/api/v1/file/content/', #{filePath})"));
        assertTrue(sql.contains("CONCAT('/api/v1/file/content/thumb_', #{filePath})"));
    }

    @Test
    void deduplicatedGrantLocksMetadataBeforeLifecycleDeletion() throws Exception {
        Select select = FileMetadataMapper.class.getMethod(
                        "selectByHashForUpdate",
                        String.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");

        assertTrue(sql.contains("WHERE file_hash = #{fileHash}"));
        assertTrue(sql.contains("FOR UPDATE"));
    }

    @Test
    void archivedAccountCannotBeReenabled() {
        User archived = user(7L, "archived-user-7-deadbeef");
        archived.setStatus(0);
        archived.setArchivedAt(LocalDateTime.now().minusDays(1));
        when(userMapper.lockById(7L)).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(archived);

        assertThrows(IllegalArgumentException.class,
                () -> service.setStatusByAdmin(7L, 1));

        verify(userMapper, never()).update(any(), any());
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

    private FileMetadata file(Long id, String path) {
        FileMetadata metadata = new FileMetadata();
        metadata.setId(id);
        metadata.setUploadUserId(7L);
        metadata.setFilePath(path);
        return metadata;
    }

    private void initializeTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) != null) return;
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
