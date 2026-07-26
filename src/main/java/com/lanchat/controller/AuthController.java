package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.config.LanChatPrivateDeploymentProperties;
import com.lanchat.dto.*;
import com.lanchat.security.LoginUser;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "lanchat_refresh";

    @Autowired
    private UserService userService;

    @Autowired(required = false)
    private LanChatPrivateDeploymentProperties privateDeploymentProperties;

    @Value("${auth.refresh-cookie.secure:false}")
    private boolean refreshCookieSecure;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpirationMillis;

    @PostMapping("/register")
    public Result<Void> register(@RequestBody RegisterDTO dto) {
        if (privateDeploymentProperties != null
                && !privateDeploymentProperties.allowsSelfRegistration()) {
            return Result.error(403, "当前私有节点已关闭自助注册，请联系管理员创建账号");
        }
        boolean success = userService.register(dto);
        if (!success) {
            return Result.error("用户名已存在");
        }
        return Result.success();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO dto, HttpServletResponse response) {
        LoginVO vo = userService.login(dto);
        if (vo == null) {
            return Result.error("用户名或密码错误");
        }
        writeRefreshCookie(response, vo.getRefreshToken(), Duration.ofMillis(refreshExpirationMillis));
        return Result.success(vo);
    }

    @PostMapping("/refresh")
    public Result<LoginVO> refreshToken(
            @RequestBody(required = false) TokenRefreshDTO dto,
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
            HttpServletResponse response) {
        if (dto == null) dto = new TokenRefreshDTO();
        if (!StringUtils.hasText(dto.getRefreshToken())) dto.setRefreshToken(refreshCookie);
        LoginVO vo = userService.refreshToken(dto);
        if (vo == null) {
            return Result.unauthorized("刷新令牌无效或已过期");
        }
        writeRefreshCookie(response, vo.getRefreshToken(), Duration.ofMillis(refreshExpirationMillis));
        return Result.success(vo);
    }

    @PostMapping("/logout")
    public Result<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
            HttpServletResponse response) {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId != null) {
            LoginUser loginUser = UserContextHolder.getCurrentUser();
            if (loginUser != null && loginUser.getToken() != null) {
                userService.logoutByToken(userId, loginUser.getToken());
            }
        }
        userService.logoutByRefreshToken(refreshCookie);
        writeRefreshCookie(response, "", Duration.ZERO);
        return Result.success();
    }

    private void writeRefreshCookie(HttpServletResponse response, String token, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, token == null ? "" : token)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
