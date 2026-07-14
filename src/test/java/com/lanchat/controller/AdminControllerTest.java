package com.lanchat.controller;

import com.lanchat.entity.User;
import com.lanchat.security.LoginUser;
import com.lanchat.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTest {

    private UserService userService;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        controller = new AdminController();
        ReflectionTestUtils.setField(controller, "userService", userService);
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
    }

    @Test
    void regularAccountCannotLoadUserList() {
        authenticateAs("alice");

        assertThrows(AccessDeniedException.class, controller::listAllUsers);
    }

    private void authenticateAs(String username) {
        LoginUser loginUser = new LoginUser(1L, username, "web", "access-token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }
}
