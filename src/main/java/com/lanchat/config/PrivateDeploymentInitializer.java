package com.lanchat.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;
import com.lanchat.mapper.DeviceLoginMapper;
import com.lanchat.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** Fails closed on unsafe private-mode configuration and creates the first admin exactly once. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PrivateDeploymentInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PrivateDeploymentInitializer.class);
    private static final String DEVELOPMENT_JWT_SECRET =
            "lan-chat-local-development-secret-change-in-production-2026";
    private static final String HISTORICAL_DEMO_PASSWORD_HASH =
            "$2a$10$VWOWjY7EBONKq/JPLNs/oO69k7SM4xG2qMskNP5MIH55T6ZwciU.C";

    private final LanChatPrivateDeploymentProperties properties;
    private final LanChatNodeProperties nodeProperties;
    private final UserMapper userMapper;
    private final DeviceLoginMapper deviceLoginMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${spring.datasource.password:}")
    private String databasePassword;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${file.path}")
    private String filePath;

    @Value("${tunnel.enabled:false}")
    private boolean tunnelEnabled;

    @Value("${websocket.allowed-origins:}")
    private String allowedWebSocketOrigins;

    public PrivateDeploymentInitializer(LanChatPrivateDeploymentProperties properties,
                                        LanChatNodeProperties nodeProperties,
                                        UserMapper userMapper,
                                        DeviceLoginMapper deviceLoginMapper,
                                        PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.nodeProperties = nodeProperties;
        this.userMapper = userMapper;
        this.deviceLoginMapper = deviceLoginMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) return;

        validatePrivateConfiguration();
        ensureBootstrapAdministrator();
        disableHistoricalDemoAccounts();
        log.info("Private deployment checks passed for node {}", nodeProperties.resolvedId());
    }

    private void validatePrivateConfiguration() {
        if (properties.isSelfRegistrationEnabled()) {
            throw unsafe("私有部署必须关闭无邀请码自助注册");
        }
        requireSecret("JWT_SECRET", jwtSecret, 32);
        if (DEVELOPMENT_JWT_SECRET.equals(jwtSecret)) {
            throw unsafe("JWT_SECRET 仍在使用开发默认值");
        }
        requireSecret("DB_PASSWORD", databasePassword, 12);
        requireSecret("REDIS_PASSWORD", redisPassword, 12);
        if (!StringUtils.hasText(allowedWebSocketOrigins)
                || allowedWebSocketOrigins.lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(",")))
                .map(String::trim)
                .anyMatch(origin -> origin.isBlank() || "*".equals(origin))) {
            throw unsafe("WEBSOCKET_ALLOWED_ORIGINS 必须是明确的 Origin，不能留空或使用通配符");
        }

        Path storage = Paths.get(filePath).toAbsolutePath().normalize();
        String normalized = storage.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("/src/main/resources/static") || normalized.endsWith("/static")) {
            throw unsafe("FILE_STORAGE_PATH 不能位于可公开访问的静态资源目录");
        }
        if (tunnelEnabled && "LOCAL_INDEPENDENT".equals(nodeProperties.normalizedMode())) {
            throw unsafe("LOCAL_INDEPENDENT 模式禁止启用公网隧道");
        }
    }

    private void ensureBootstrapAdministrator() {
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        query.eq(User::getUsername, "admin").last("LIMIT 1");
        User existing = userMapper.selectOne(query);
        if (existing != null) {
            if (rotateHistoricalDemoPassword(existing)) return;
            log.info("Existing administrator account retained; bootstrap password was not reapplied");
            return;
        }

        String password = properties.getBootstrapAdminPassword();
        validateAdminPassword(password);

        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(password));
        admin.setNickname(safeNickname(properties.getBootstrapAdminNickname()));
        admin.setAvatar("");
        admin.setSignature("");
        admin.setOnline(0);
        admin.setStatus(1);
        admin.setCreateTime(LocalDateTime.now());
        if (userMapper.insert(admin) != 1) {
            throw new IllegalStateException("私有部署初始化失败：无法创建管理员账号");
        }
        log.info("Created bootstrap administrator account for a new private deployment");
    }

    private boolean rotateHistoricalDemoPassword(User admin) {
        validateExistingAdministratorHash(admin.getPassword());
        boolean usesDemoPassword;
        try {
            usesDemoPassword = StringUtils.hasText(admin.getPassword())
                    && passwordEncoder.matches("LanChat123!", admin.getPassword());
        } catch (RuntimeException exception) {
            throw unsafe("现有管理员密码哈希无效");
        }
        if (!usesDemoPassword) return false;

        String replacement = properties.getBootstrapAdminPassword();
        validateAdminPassword(replacement);
        UpdateWrapper<User> userUpdate = new UpdateWrapper<>();
        userUpdate.eq("id", admin.getId())
                .set("password", passwordEncoder.encode(replacement));
        if (userMapper.update(null, userUpdate) != 1) {
            throw new IllegalStateException("私有部署初始化失败：无法轮换历史演示管理员密码");
        }

        UpdateWrapper<DeviceLogin> deviceUpdate = new UpdateWrapper<>();
        deviceUpdate.eq("user_id", admin.getId())
                .eq("status", 1)
                .set("status", 0);
        deviceLoginMapper.update(null, deviceUpdate);
        log.warn("Replaced the historical demo administrator password and revoked prior device sessions");
        return true;
    }

    private void validateExistingAdministratorHash(String passwordHash) {
        if (!StringUtils.hasText(passwordHash)
                || !passwordHash.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$")) {
            throw unsafe("现有管理员密码哈希无效");
        }
        int strength = Integer.parseInt(passwordHash.substring(4, 6));
        if (strength < 10 || strength > 16) {
            throw unsafe("现有管理员 BCrypt 强度不在安全范围内");
        }
    }

    private void disableHistoricalDemoAccounts() {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("password", HISTORICAL_DEMO_PASSWORD_HASH)
                .ne("username", "admin")
                .eq("status", 1);
        List<User> demoAccounts = userMapper.selectList(query);
        if (demoAccounts == null || demoAccounts.isEmpty()) return;

        List<Long> userIds = demoAccounts.stream().map(User::getId).toList();
        UpdateWrapper<User> userUpdate = new UpdateWrapper<>();
        userUpdate.in("id", userIds).eq("status", 1).set("status", 0);
        if (userMapper.update(null, userUpdate) != demoAccounts.size()) {
            throw new IllegalStateException("私有部署初始化失败：无法停用历史演示账号");
        }

        UpdateWrapper<DeviceLogin> deviceUpdate = new UpdateWrapper<>();
        deviceUpdate.in("user_id", userIds).eq("status", 1).set("status", 0);
        deviceLoginMapper.update(null, deviceUpdate);
        log.warn("Disabled {} historical demo accounts and revoked their device sessions", demoAccounts.size());
    }

    private void validateAdminPassword(String password) {
        requireSecret("LANCHAT_BOOTSTRAP_ADMIN_PASSWORD", password, 12);
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 72) throw unsafe("管理员密码的 UTF-8 长度不能超过 72 字节");
        if (!password.matches(".*[a-z].*")
                || !password.matches(".*[A-Z].*")
                || !password.matches(".*\\d.*")
                || !password.matches(".*[^a-zA-Z0-9].*")) {
            throw unsafe("管理员密码必须同时包含大写字母、小写字母、数字和符号");
        }
    }

    private void requireSecret(String name, String value, int minimumLength) {
        if (!StringUtils.hasText(value) || value.length() < minimumLength) {
            throw unsafe(name + " 至少需要 " + minimumLength + " 个字符");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("change-me") || normalized.contains("changeme")
                || normalized.contains("replace-me") || normalized.contains("password")) {
            throw unsafe(name + " 不能使用示例或占位值");
        }
    }

    private String safeNickname(String nickname) {
        String value = StringUtils.hasText(nickname) ? nickname.trim() : "系统管理员";
        value = value.replaceAll("[\\p{Cntrl}]", "");
        if (value.length() < 2) value = "系统管理员";
        return value.substring(0, Math.min(16, value.length()));
    }

    private IllegalStateException unsafe(String reason) {
        return new IllegalStateException("私有部署安全校验失败：" + reason);
    }
}
