package com.lanchat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.dto.LoginDTO;
import com.lanchat.dto.LoginVO;
import com.lanchat.dto.TokenRefreshDTO;
import com.lanchat.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private UserService userService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        controller = new AuthController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "refreshCookieSecure", false);
        ReflectionTestUtils.setField(controller, "refreshExpirationMillis", 604_800_000L);
    }

    @Test
    void loginMovesRefreshTokenToHttpOnlyCookie() throws Exception {
        LoginVO login = loginResult();
        when(userService.login(any(LoginDTO.class))).thenReturn(login);
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = controller.login(new LoginDTO(), response);

        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("lanchat_refresh=refresh-secret"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        String body = new ObjectMapper().writeValueAsString(result);
        assertFalse(body.contains("refresh-secret"));
        assertFalse(body.contains("refreshToken"));
    }

    @Test
    void refreshReadsTokenFromCookieAndRotatesIt() {
        LoginVO rotated = loginResult();
        rotated.setRefreshToken("rotated-secret");
        when(userService.refreshToken(any(TokenRefreshDTO.class))).thenReturn(rotated);
        MockHttpServletResponse response = new MockHttpServletResponse();
        TokenRefreshDTO request = new TokenRefreshDTO();

        controller.refreshToken(request, "cookie-secret", response);

        assertTrue(response.getHeader("Set-Cookie").contains("rotated-secret"));
        verify(userService).refreshToken(request);
        assertTrue("cookie-secret".equals(request.getRefreshToken()));
    }

    @Test
    void logoutRevokesRefreshTokenAndExpiresCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout("cookie-secret", response);

        verify(userService).logoutByRefreshToken("cookie-secret");
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("lanchat_refresh="));
        assertTrue(cookie.contains("Max-Age=0"));
        assertTrue(cookie.contains("HttpOnly"));
    }

    private LoginVO loginResult() {
        LoginVO login = new LoginVO();
        login.setUserId(7L);
        login.setUsername("alice");
        login.setNickname("Alice");
        login.setToken("access-secret");
        login.setRefreshToken("refresh-secret");
        login.setExpiresIn(7_200L);
        return login;
    }
}
