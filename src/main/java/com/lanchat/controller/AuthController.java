package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.*;
import com.lanchat.security.LoginUser;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin
public class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public Result<Void> register(@RequestBody RegisterDTO dto) {
        boolean success = userService.register(dto);
        if (!success) {
            return Result.error("用户名已存在");
        }
        return Result.success();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO dto) {
        LoginVO vo = userService.login(dto);
        if (vo == null) {
            return Result.error("用户名或密码错误");
        }
        return Result.success(vo);
    }

    @PostMapping("/refresh")
    public Result<LoginVO> refreshToken(@RequestBody TokenRefreshDTO dto) {
        LoginVO vo = userService.refreshToken(dto);
        if (vo == null) {
            return Result.unauthorized("刷新令牌无效或已过期");
        }
        return Result.success(vo);
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        Long userId = UserContextHolder.getCurrentUserId();
        if (userId != null) {
            LoginUser loginUser = UserContextHolder.getCurrentUser();
            if (loginUser != null && loginUser.getToken() != null) {
                userService.logoutByToken(userId, loginUser.getToken());
            }
        }
        return Result.success();
    }
}
