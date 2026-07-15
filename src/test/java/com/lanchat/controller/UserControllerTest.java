package com.lanchat.controller;

import com.lanchat.entity.DeviceLogin;
import com.lanchat.security.LoginUser;
import com.lanchat.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserControllerTest {

    private UserService userService;
    private UserController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deviceListMarksTheDeviceThatOwnsTheCurrentAccessToken() {
        authenticate(7L, "alice", "web", "current-access-token");
        DeviceLogin current = device(12L, 7L, "web");
        DeviceLogin other = device(21L, 7L, "android");
        when(userService.getActiveDevice("current-access-token", 7L, "web")).thenReturn(current);
        when(userService.getDevices(7L)).thenReturn(List.of(current, other));

        var result = controller.getDevices();

        assertEquals(200, result.getCode());
        assertEquals(2, result.getData().size());
        assertTrue(result.getData().get(0).current());
        assertFalse(result.getData().get(1).current());
        verify(userService).getActiveDevice("current-access-token", 7L, "web");
    }

    @Test
    void deviceListRequiresAuthentication() {
        var result = controller.getDevices();

        assertEquals(401, result.getCode());
        verifyNoInteractions(userService);
    }

    private DeviceLogin device(Long id, Long userId, String deviceType) {
        DeviceLogin device = new DeviceLogin();
        device.setId(id);
        device.setUserId(userId);
        device.setDeviceType(deviceType);
        device.setDeviceName(deviceType + " device");
        device.setStatus(1);
        return device;
    }

    private void authenticate(Long userId, String username, String deviceType, String token) {
        LoginUser loginUser = new LoginUser(userId, username, deviceType, token);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }
}
