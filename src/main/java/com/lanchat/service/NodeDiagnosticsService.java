package com.lanchat.service;

import com.lanchat.config.LanChatNodeProperties;
import com.lanchat.config.LanChatPrivateDeploymentProperties;
import com.lanchat.dto.AdminDiagnostics;
import com.lanchat.dto.NodePublicInfo;
import com.lanchat.websocket.ChatWebSocketHandler;
import com.lanchat.service.storage.FileObjectStorage;
import com.lanchat.service.storage.FileObjectStorageRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects sanitized user metadata and privileged node health diagnostics. */
@Service
public class NodeDiagnosticsService {

    private final LanChatNodeProperties nodeProperties;
    private final LanChatPrivateDeploymentProperties privateDeploymentProperties;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final Instant startedAt = Instant.now();

    @Value("${file.path}")
    private String filePath;

    @Autowired(required = false)
    private FileObjectStorageRegistry storageRegistry;

    @Value("${lanchat.discovery.enabled:false}")
    private boolean discoveryEnabled;

    public NodeDiagnosticsService(LanChatNodeProperties nodeProperties,
                                  LanChatPrivateDeploymentProperties privateDeploymentProperties,
                                  JdbcTemplate jdbcTemplate,
                                  StringRedisTemplate redisTemplate) {
        this.nodeProperties = nodeProperties;
        this.privateDeploymentProperties = privateDeploymentProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    public NodePublicInfo publicInfo() {
        return new NodePublicInfo(
                nodeProperties.resolvedId(),
                safeLabel(nodeProperties.getName(), "LanChat Node"),
                safeLabel(nodeProperties.getOrganizationName(), "Local Organization"),
                safeLabel(nodeProperties.getVersion(), "unknown"),
                nodeProperties.normalizedMode(),
                "AVAILABLE",
                nodeProperties.isSecure(),
                discoveryEnabled,
                privateDeploymentProperties.allowsSelfRegistration(),
                Set.of("PASSWORD"),
                Set.of(
                        "RELIABLE_MESSAGING",
                        "OFFLINE_OUTBOX",
                        "FILE_SECURITY",
                        "RESUMABLE_UPLOAD",
                        "OBJECT_STORAGE",
                        "CONNECTION_DIAGNOSTICS",
                        "PRIVATE_DEPLOYMENT",
                        "MDNS_DISCOVERY",
                        "MULTI_INSTANCE_ROUTING"
                ),
                System.currentTimeMillis()
        );
    }

    /** Lightweight liveness information. It intentionally exposes no dependency addresses. */
    public Map<String, Object> publicHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("nodeId", nodeProperties.resolvedId());
        health.put("version", safeLabel(nodeProperties.getVersion(), "unknown"));
        health.put("uptimeSeconds", Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds()));
        health.put("serverTime", System.currentTimeMillis());
        return health;
    }

    public AdminDiagnostics adminDiagnostics() {
        AdminDiagnostics.DependencyStatus database = checkDatabase();
        AdminDiagnostics.DependencyStatus redis = checkRedis();
        AdminDiagnostics.StorageStatus storage = checkStorage();
        AdminDiagnostics.JvmStatus jvm = jvmStatus();
        ChatWebSocketHandler.WebSocketMetrics webSocketMetrics = ChatWebSocketHandler.metricsSnapshot();

        List<String> warnings = new ArrayList<>();
        if (!"UP".equals(database.status())) warnings.add("数据库连接异常");
        if (!"UP".equals(redis.status())) warnings.add("Redis 不可用，在线状态与短期预览会降级");
        if (!"UP".equals(storage.status())) warnings.add("文件存储不可用");
        if (storage.totalBytes() > 0 && storage.usableBytes() * 100 / storage.totalBytes() < 10) {
            warnings.add("文件存储剩余空间低于 10%");
        }

        return new AdminDiagnostics(
                nodeProperties.resolvedId(),
                safeLabel(nodeProperties.getName(), "LanChat Node"),
                nodeProperties.normalizedMode(),
                safeLabel(nodeProperties.getVersion(), "unknown"),
                startedAt,
                Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds()),
                ChatWebSocketHandler.getOnlineCount(),
                ChatWebSocketHandler.getOnlineConnectionCount(),
                webSocketMetrics.events(),
                webSocketMetrics.acknowledgements(),
                webSocketMetrics.failures(),
                round(webSocketMetrics.averageProcessingMs()),
                database,
                redis,
                storage,
                jvm,
                webSocketMetrics.recentConnections(),
                List.copyOf(warnings)
        );
    }

    private AdminDiagnostics.DependencyStatus checkDatabase() {
        long started = System.nanoTime();
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (!Integer.valueOf(1).equals(value)) throw new IllegalStateException("unexpected response");
            return new AdminDiagnostics.DependencyStatus("UP", elapsedMs(started), null);
        } catch (Exception exception) {
            return new AdminDiagnostics.DependencyStatus(
                    "DOWN", elapsedMs(started), exception.getClass().getSimpleName());
        }
    }

    private AdminDiagnostics.DependencyStatus checkRedis() {
        long started = System.nanoTime();
        try {
            String response = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            if (!"PONG".equalsIgnoreCase(response)) throw new IllegalStateException("unexpected response");
            return new AdminDiagnostics.DependencyStatus("UP", elapsedMs(started), null);
        } catch (Exception exception) {
            return new AdminDiagnostics.DependencyStatus(
                    "DOWN", elapsedMs(started), exception.getClass().getSimpleName());
        }
    }

    private AdminDiagnostics.StorageStatus checkStorage() {
        if (storageRegistry != null && !"LOCAL".equals(storageRegistry.activeType())) {
            FileObjectStorage storage = storageRegistry.active();
            try {
                storage.checkAvailable();
                return new AdminDiagnostics.StorageStatus(
                        "UP", storage.location(), 0, 0, 0, 0, null);
            } catch (Exception exception) {
                return new AdminDiagnostics.StorageStatus(
                        "DOWN", storage.location(), 0, 0, 0, 0,
                        exception.getClass().getSimpleName());
            }
        }
        Path storage = Paths.get(filePath).toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(storage) || !Files.isReadable(storage) || !Files.isWritable(storage)) {
                return new AdminDiagnostics.StorageStatus(
                        "DOWN", storage.toString(), 0, 0, 0, 0, "目录不存在或不可读写");
            }
            FileStore store = Files.getFileStore(storage);
            long total = Math.max(0, store.getTotalSpace());
            long usable = Math.max(0, store.getUsableSpace());
            long used = Math.max(0, total - usable);
            double usedPercent = total == 0 ? 0 : round(used * 100.0 / total);
            return new AdminDiagnostics.StorageStatus(
                    "UP", storage.toString(), total, usable, used, usedPercent, null);
        } catch (Exception exception) {
            return new AdminDiagnostics.StorageStatus(
                    "DOWN", storage.toString(), 0, 0, 0, 0, exception.getClass().getSimpleName());
        }
    }

    private AdminDiagnostics.JvmStatus jvmStatus() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Runtime runtime = Runtime.getRuntime();
        return new AdminDiagnostics.JvmStatus(
                Math.max(0, memory.getHeapMemoryUsage().getUsed()),
                Math.max(0, memory.getHeapMemoryUsage().getMax()),
                threads.getThreadCount(),
                runtime.availableProcessors(),
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()
        );
    }

    private Long elapsedMs(long startedNanos) {
        return Math.max(0, Math.round((System.nanoTime() - startedNanos) / 1_000_000.0));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String safeLabel(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String sanitized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return sanitized.substring(0, Math.min(80, sanitized.length()));
    }
}
