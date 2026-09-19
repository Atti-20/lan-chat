package com.lanchat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.entity.User;
import com.lanchat.mapper.UserMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A technical, non-interactive account used exclusively for broadcast cards
 * and manual reminder messages. Its password is generated in-process and is
 * never configured, logged, or returned to a client.
 */
@Component
public class BroadcastNotificationAccountService implements ApplicationRunner {

    public static final String USERNAME = "broadcast-notify";
    public static final String NICKNAME = "广播通知";
    public static final String SIGNATURE = "系统广播通知账户";
    public static final String AVATAR = "letter:广:#5856D6";

    private static final Logger log = LoggerFactory.getLogger(BroadcastNotificationAccountService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthorizationService authorizationService;

    public BroadcastNotificationAccountService(UserMapper userMapper,
                                               PasswordEncoder passwordEncoder,
                                               AuthorizationService authorizationService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.authorizationService = authorizationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureAccount();
        } catch (IllegalStateException conflict) {
            // A legacy user may have claimed the reserved name before this
            // service existed. Never silently take that account over: doing so
            // would turn an account with a user-controlled password into a
            // reader of every broadcast notification conversation.
            log.error("无法初始化广播通知账户: {}", conflict.getMessage());
        }
    }

    @Transactional
    public User ensureAccount() {
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, USERNAME)
                .last("LIMIT 1"));
        if (existing != null) {
            if (!isNotificationAccount(existing)) {
                throw new IllegalStateException("保留用户名 broadcast-notify 已被普通账户占用，拒绝接管");
            }
            if (!Integer.valueOf(1).equals(existing.getStatus())
                    || !NICKNAME.equals(existing.getNickname())
                    || !AVATAR.equals(existing.getAvatar())
                    || !SIGNATURE.equals(existing.getSignature())
                    || !Integer.valueOf(0).equals(existing.getCanSendBroadcast())) {
                existing.setStatus(1);
                existing.setNickname(NICKNAME);
                existing.setAvatar(AVATAR);
                existing.setSignature(SIGNATURE);
                existing.setCanSendBroadcast(0);
                existing.setUpdateTime(LocalDateTime.now());
                userMapper.updateById(existing);
            }
            authorizationService.provisionMember(existing.getId());
            return existing;
        }

        User account = new User();
        account.setUsername(USERNAME);
        account.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        account.setNickname(NICKNAME);
        account.setAvatar(AVATAR);
        account.setSignature(SIGNATURE);
        account.setOnline(0);
        account.setStatus(1);
        account.setCanSendBroadcast(0);
        account.setCreateTime(LocalDateTime.now());
        try {
            userMapper.insert(account);
        } catch (DuplicateKeyException duplicate) {
            User concurrent = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, USERNAME)
                    .last("LIMIT 1"));
            if (concurrent == null) throw duplicate;
            if (!isNotificationAccount(concurrent)) {
                throw new IllegalStateException("保留用户名 broadcast-notify 已被普通账户占用，拒绝接管", duplicate);
            }
            authorizationService.provisionMember(concurrent.getId());
            return concurrent;
        }
        authorizationService.provisionMember(account.getId());
        return account;
    }

    public static boolean isNotificationAccount(User user) {
        return user != null
                && USERNAME.equalsIgnoreCase(user.getUsername())
                && SIGNATURE.equals(user.getSignature());
    }
}
