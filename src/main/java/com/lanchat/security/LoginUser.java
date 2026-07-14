package com.lanchat.security;

/**
 * 登录用户信息（存储在 SecurityContext 中）
 */
public class LoginUser {
    private final Long userId;
    private final String username;
    private final String deviceType;
    private final String token;

    public LoginUser(Long userId, String username, String deviceType) {
        this(userId, username, deviceType, null);
    }

    public LoginUser(Long userId, String username, String deviceType, String token) {
        this.userId = userId;
        this.username = username;
        this.deviceType = deviceType;
        this.token = token;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public String getToken() {
        return token;
    }
}
