package com.lanchat.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 用户上下文工具：从 SecurityContext 获取当前登录用户
 */
public class UserContextHolder {

    private UserContextHolder() {}

    /**
     * 获取当前登录用户
     */
    public static LoginUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser) {
            return (LoginUser) authentication.getPrincipal();
        }
        return null;
    }

    /**
     * 获取当前登录用户ID
     */
    public static Long getCurrentUserId() {
        LoginUser user = getCurrentUser();
        return user != null ? user.getUserId() : null;
    }
}
