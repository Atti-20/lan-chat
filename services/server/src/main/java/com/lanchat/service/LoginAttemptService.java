package com.lanchat.service;

/**
 * 登录尝试限制服务
 * 基于Redis实现：连续5次失败锁定30分钟
 */
public interface LoginAttemptService {

    /** 检查账号是否被锁定 */
    boolean isLocked(String username);

    /** 获取剩余锁定时间（分钟） */
    long getRemainingLockTime(String username);

    /** 记录一次失败登录 */
    void recordFailedAttempt(String username);

    /** 登录成功时清除计数 */
    void clearAttempts(String username);

    /** 获取当前失败次数 */
    int getFailedAttempts(String username);
}
