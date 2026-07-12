package com.lanchat.service.impl;

import com.lanchat.service.LoginAttemptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 登录尝试限制服务实现
 * 规则：连续5次密码错误，锁定账号30分钟
 */
@Service
public class LoginAttemptServiceImpl implements LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_TIME_MINUTES = 30;
    private static final String KEY_PREFIX = "login:attempt:";
    private static final String LOCK_PREFIX = "login:lock:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public boolean isLocked(String username) {
        String lockKey = LOCK_PREFIX + username;
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey));
    }

    @Override
    public long getRemainingLockTime(String username) {
        String lockKey = LOCK_PREFIX + username;
        Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.MINUTES);
        return ttl != null ? ttl : 0;
    }

    @Override
    public void recordFailedAttempt(String username) {
        String attemptKey = KEY_PREFIX + username;
        String lockKey = LOCK_PREFIX + username;

        Long attempts = redisTemplate.opsForValue().increment(attemptKey);

        // 首次失败时设置过期时间为1小时（超过锁定时长后自动清理）
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(attemptKey, 1, TimeUnit.HOURS);
        }

        // 达到最大失败次数则锁定
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            redisTemplate.opsForValue().set(lockKey, "1", LOCK_TIME_MINUTES, TimeUnit.MINUTES);
            redisTemplate.delete(attemptKey);
        }
    }

    @Override
    public void clearAttempts(String username) {
        redisTemplate.delete(KEY_PREFIX + username);
        redisTemplate.delete(LOCK_PREFIX + username);
    }

    @Override
    public int getFailedAttempts(String username) {
        String attempts = redisTemplate.opsForValue().get(KEY_PREFIX + username);
        return attempts != null ? Integer.parseInt(attempts) : 0;
    }
}
