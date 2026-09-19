package com.lanchat.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-with-at-least-thirty-two-bytes");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 60_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 120_000L);
        ReflectionTestUtils.setField(jwtUtil, "issuer", "lanchat-test-node");
        ReflectionTestUtils.setField(jwtUtil, "audience", "lanchat-test-client");
        jwtUtil.init();
    }

    @Test
    void accessAndRefreshTokensCannotBeUsedInterchangeably() {
        String accessToken = jwtUtil.generateToken(7L, "alice", "web");
        String refreshToken = jwtUtil.generateRefreshToken(7L, "web");

        assertTrue(jwtUtil.isAccessToken(accessToken));
        assertFalse(jwtUtil.isRefreshToken(accessToken));
        assertTrue(jwtUtil.isRefreshToken(refreshToken));
        assertFalse(jwtUtil.isAccessToken(refreshToken));

        String secondAccessToken = jwtUtil.generateToken(7L, "alice", "web");
        assertNotEquals(accessToken, secondAccessToken, "同一秒签发的令牌也必须唯一");
    }
}
