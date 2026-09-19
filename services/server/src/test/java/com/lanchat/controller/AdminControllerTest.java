package com.lanchat.controller;

import com.lanchat.entity.User;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.control.rbac.PermissionCode;
import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.admin.ControlAdminOperationService;
import com.lanchat.dto.AdminPhysicalErasureDTO;
import com.lanchat.dto.AdminResetPasswordDTO;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.dto.RuntimeLogSnapshot;
import com.lanchat.security.LoginUser;
import com.lanchat.service.RuntimeLogService;
import com.lanchat.service.UserService;
import com.lanchat.websocket.ChatWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class AdminControllerTest {

    private UserService userService;
    private RuntimeLogService runtimeLogService;
    private ChatWebSocketHandler webSocketHandler;
    private AuthorizationService authorizationService;
    private ControlAuditService controlAuditService;
    private ControlAdminOperationService adminOperationService;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        runtimeLogService = mock(RuntimeLogService.class);
        webSocketHandler = mock(ChatWebSocketHandler.class);
        authorizationService = mock(AuthorizationService.class);
        controlAuditService = mock(ControlAuditService.class);
        adminOperationService = mock(ControlAdminOperationService.class);
        controller = new AdminController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "runtimeLogService", runtimeLogService);
        ReflectionTestUtils.setField(controller, "webSocketHandler", webSocketHandler);
        ReflectionTestUtils.setField(controller, "authorizationService", authorizationService);
        ReflectionTestUtils.setField(controller, "controlAuditService", controlAuditService);
        ReflectionTestUtils.setField(controller, "adminOperationService", adminOperationService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminAccountCanLoadUserList() {
        authenticateAs("admin");
        User user = new User();
        user.setId(2L);
        user.setUsername("alice");
        when(userService.list()).thenReturn(List.of(user));

        var result = controller.listAllUsers();

        assertEquals(200, result.getCode());
        assertEquals(List.of(user), result.getData());
        verify(userService).list();
        verify(authorizationService).requireCurrentUserPermission(PermissionCode.USER_READ);
    }

    @Test
    void permissionGrantDoesNotDependOnHistoricalAdministratorUsername() {
        authenticateAuthorizedUser("operations.lead");
        when(userService.list()).thenReturn(List.of());

        var result = controller.listAllUsers();

        assertEquals(200, result.getCode());
        verify(authorizationService).requireCurrentUserPermission(PermissionCode.USER_READ);
    }

    @Test
    void regularAccountCannotLoadUserList() {
        authenticateAs("alice");

        assertThrows(AccessDeniedException.class, controller::listAllUsers);
    }

    @Test
    void administratorCanCreateRegularAccount() {
        authenticateAs("admin");
        RegisterDTO request = new RegisterDTO();
        request.setUsername("new.member");
        request.setNickname("新成员");
        request.setPassword("Member1234");
        when(adminOperationService.createUser(request)).thenReturn(true);

        var result = controller.createUser(request);

        assertEquals(200, result.getCode());
        verify(adminOperationService).createUser(request);
    }

    @Test
    void administratorCanResetRegularAccountPassword() {
        authenticateAs("admin");
        AdminResetPasswordDTO request = new AdminResetPasswordDTO();
        request.setNewPassword("Member5678");
        when(adminOperationService.resetPassword(7L, "Member5678")).thenReturn(true);

        var result = controller.resetUserPassword(7L, request);

        assertEquals(200, result.getCode());
        verify(adminOperationService).resetPassword(7L, "Member5678");
    }

    @Test
    void defaultDeleteArchivesWithoutCallingPhysicalErasure() {
        authenticateAs("admin");
        when(adminOperationService.archiveUser(7L, 1L)).thenReturn(true);

        var result = controller.deleteUser(7L);

        assertEquals(200, result.getCode());
        assertEquals("用户已归档，历史消息与广播回执已保留", result.getData());
        verify(adminOperationService).archiveUser(7L, 1L);
        verify(adminOperationService, never()).physicallyEraseUser(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void physicalErasureForwardsExactConfirmationAndReason() {
        authenticateAs("admin");
        AdminPhysicalErasureDTO request = new AdminPhysicalErasureDTO();
        request.setConfirmationPhrase("ERASE USER 7");
        request.setReason("用户书面请求数据擦除");
        when(adminOperationService.physicallyEraseUser(
                7L, 1L, "ERASE USER 7", "用户书面请求数据擦除")).thenReturn(true);

        var result = controller.physicallyEraseUser(7L, request);

        assertEquals(200, result.getCode());
        verify(adminOperationService).physicallyEraseUser(
                7L, 1L, "ERASE USER 7", "用户书面请求数据擦除");
    }

    @Test
    void physicalErasureConflictIsExplicitWhenArchiveWasSkipped() {
        authenticateAs("admin");
        AdminPhysicalErasureDTO request = new AdminPhysicalErasureDTO();
        request.setConfirmationPhrase("ERASE USER 7");
        request.setReason("用户书面请求数据擦除");
        doThrow(new IllegalArgumentException("账号必须先归档，才能执行物理擦除"))
                .when(adminOperationService).physicallyEraseUser(
                        7L, 1L, "ERASE USER 7", "用户书面请求数据擦除");

        var result = controller.physicallyEraseUser(7L, request);

        assertEquals(409, result.getCode());
    }

    @Test
    void regularAccountCannotPhysicallyEraseUser() {
        authenticateAs("alice");

        assertThrows(AccessDeniedException.class,
                () -> controller.physicallyEraseUser(7L, new AdminPhysicalErasureDTO()));
    }

    @Test
    void administratorDisableUsesTransactionalSessionLifecycleService() {
        authenticateAs("admin");
        User target = new User();
        target.setId(7L);
        target.setUsername("alice");
        when(userService.getById(7L)).thenReturn(target);
        when(adminOperationService.setStatus(7L, 0)).thenReturn(true);

        var result = controller.changUserStatus(7L, 0);

        assertEquals(200, result.getCode());
        verify(adminOperationService).setStatus(7L, 0);
        verify(userService, never()).updateById(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void administratorCanGrantBroadcastPermission() {
        authenticateAs("admin");
        when(adminOperationService.setBroadcastPermission(7L, true)).thenReturn(true);

        var result = controller.setBroadcastPermission(7L, true);

        assertEquals(200, result.getCode());
        verify(adminOperationService).setBroadcastPermission(7L, true);
        verify(webSocketHandler).sendBroadcastPermissionUpdated(7L, true);
    }

    @Test
    void administratorCanRevokeBroadcastPermission() {
        authenticateAs("admin");
        when(adminOperationService.setBroadcastPermission(7L, false)).thenReturn(true);

        var result = controller.setBroadcastPermission(7L, false);

        assertEquals(200, result.getCode());
        verify(adminOperationService).setBroadcastPermission(7L, false);
        verify(webSocketHandler).sendBroadcastPermissionUpdated(7L, false);
    }

    @Test
    void missingBroadcastPermissionTargetReturnsNotFound() {
        authenticateAs("admin");
        doThrow(new IllegalArgumentException("用户不存在"))
                .when(adminOperationService).setBroadcastPermission(99L, true);

        var result = controller.setBroadcastPermission(99L, true);

        assertEquals(404, result.getCode());
        verify(webSocketHandler, never()).sendBroadcastPermissionUpdated(99L, true);
    }

    @Test
    void rootAdministratorBroadcastPermissionReturnsBadRequest() {
        authenticateAs("admin");
        doThrow(new IllegalArgumentException("管理员默认拥有广播权限，不能修改"))
                .when(adminOperationService).setBroadcastPermission(1L, false);

        var result = controller.setBroadcastPermission(1L, false);

        assertEquals(400, result.getCode());
        verify(webSocketHandler, never()).sendBroadcastPermissionUpdated(1L, false);
    }

    @Test
    void regularAccountCannotChangeBroadcastPermission() {
        authenticateAs("alice");

        assertThrows(AccessDeniedException.class,
                () -> controller.setBroadcastPermission(7L, true));
    }

    @Test
    void regularAccountCannotResetAnotherPassword() {
        authenticateAs("alice");
        AdminResetPasswordDTO request = new AdminResetPasswordDTO();
        request.setNewPassword("Member5678");

        assertThrows(AccessDeniedException.class,
                () -> controller.resetUserPassword(7L, request));
    }

    @Test
    void administratorCanReadRuntimeLogs() {
        authenticateAs("admin");
        RuntimeLogSnapshot snapshot = new RuntimeLogSnapshot(
                true,
                "lan-chat.log",
                128,
                null,
                1,
                false,
                Map.of("ERROR", 1L, "WARN", 0L, "INFO", 0L, "DEBUG", 0L, "TRACE", 0L),
                List.of(),
                "ready"
        );
        when(runtimeLogService.read(200, "ERROR", "mysql")).thenReturn(snapshot);

        var result = controller.runtimeLogs(200, "ERROR", "mysql");

        assertEquals(200, result.getCode());
        assertEquals(snapshot, result.getData());
        verify(runtimeLogService).read(200, "ERROR", "mysql");
    }

    @Test
    void regularAccountCannotReadRuntimeLogs() {
        authenticateAs("alice");

        assertThrows(AccessDeniedException.class,
                () -> controller.runtimeLogs(200, "ALL", ""));
    }

    private void authenticateAs(String username) {
        authenticateAuthorizedUser(username);
        if (!"admin".equals(username)) {
            doThrow(new AccessDeniedException("missing permission"))
                    .when(authorizationService).requireCurrentUserPermission(any(PermissionCode.class));
        }
    }

    private void authenticateAuthorizedUser(String username) {
        LoginUser loginUser = new LoginUser(1L, username, "web", "access-token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }
}
