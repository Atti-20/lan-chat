package com.lanchat.service;

import com.lanchat.dto.RuntimeLogSnapshot;
import com.lanchat.dto.RuntimeLogSnapshot.RuntimeLogEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only the configured active log file and converts its bounded tail into admin-safe records. */
@Service
public class RuntimeLogService {
    private static final int MIN_READ_BYTES = 64 * 1024;
    private static final int MAX_READ_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LIMIT = 1_000;
    private static final int MAX_MESSAGE_LENGTH = 4_000;
    private static final int MAX_DETAILS_LENGTH = 20_000;
    private static final Set<String> LEVELS = Set.of("ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final Pattern LOG_HEADER = Pattern.compile(
            "^(?<timestamp>\\S+)\\s+(?<level>TRACE|DEBUG|INFO|WARN|ERROR)\\s+" +
                    "\\[(?<thread>[^]]*)]\\s+\\[requestId=(?<requestId>[^]]*)]\\s+" +
                    "(?<logger>\\S+)\\s+-\\s*(?<message>.*)$");
    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path logFile;
    private final int maxReadBytes;

    public RuntimeLogService(
            @Value("${logging.file.name:./logs/lan-chat.log}") String configuredLogFile,
            @Value("${lanchat.runtime-logs.max-read-bytes:4194304}") int configuredMaxReadBytes) {
        this.logFile = Paths.get(configuredLogFile).toAbsolutePath().normalize();
        this.maxReadBytes = Math.max(MIN_READ_BYTES, Math.min(MAX_READ_BYTES, configuredMaxReadBytes));
    }

    public RuntimeLogSnapshot read(int requestedLimit, String requestedLevel, String requestedKeyword) {
        int limit = Math.max(20, Math.min(MAX_LIMIT, requestedLimit));
        String level = normalizeLevel(requestedLevel);
        String keyword = normalizeKeyword(requestedKeyword);

        if (!Files.isRegularFile(logFile) || !Files.isReadable(logFile)) {
            return unavailableSnapshot();
        }

        try {
            long fileSize = Files.size(logFile);
            Instant updatedAt = Files.getLastModifiedTime(logFile).toInstant();
            TailContent tail = readTail(fileSize);
            List<RuntimeLogEntry> parsed = parse(tail);
            Map<String, Long> counts = emptyCounts();
            parsed.forEach(entry -> counts.computeIfPresent(entry.level(), (ignored, count) -> count + 1));

            List<RuntimeLogEntry> matching = parsed.stream()
                    .filter(entry -> "ALL".equals(level) || level.equals(entry.level()))
                    .filter(entry -> keyword.isEmpty() || searchableText(entry).contains(keyword))
                    .toList();
            int first = Math.max(0, matching.size() - limit);
            List<RuntimeLogEntry> latestFirst = new ArrayList<>(matching.subList(first, matching.size()));
            Collections.reverse(latestFirst);

            boolean truncated = tail.truncated() || matching.size() > limit;
            String notice = tail.truncated()
                    ? "日志较大，当前页面扫描最近的 " + readableMegabytes(maxReadBytes) + "；导出文件可查看完整内容。"
                    : "展示当前进程的启动与运行日志，页面每 10 秒自动刷新。";
            return new RuntimeLogSnapshot(
                    true,
                    logFile.getFileName().toString(),
                    fileSize,
                    updatedAt,
                    parsed.size(),
                    truncated,
                    counts,
                    latestFirst,
                    notice
            );
        } catch (IOException exception) {
            throw new IllegalStateException("运行日志读取失败", exception);
        }
    }

    public Optional<LogExport> openExport() {
        if (!Files.isRegularFile(logFile) || !Files.isReadable(logFile)) return Optional.empty();
        try {
            Resource resource = new InputStreamResource(Files.newInputStream(logFile, StandardOpenOption.READ));
            String fileName = "lan-chat-runtime-" + LocalDateTime.now().format(EXPORT_TIME) + ".log";
            return Optional.of(new LogExport(resource, fileName));
        } catch (IOException exception) {
            throw new IllegalStateException("运行日志导出失败", exception);
        }
    }

    private RuntimeLogSnapshot unavailableSnapshot() {
        return new RuntimeLogSnapshot(
                false,
                logFile.getFileName().toString(),
                0,
                null,
                0,
                false,
                emptyCounts(),
                List.of(),
                "日志文件尚未生成；服务输出第一条运行信息后即可查看。"
        );
    }

    private TailContent readTail(long fileSize) throws IOException {
        int bytesToRead = (int) Math.min(fileSize, maxReadBytes);
        long startOffset = Math.max(0, fileSize - bytesToRead);
        ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ)) {
            channel.position(startOffset);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) break;
                if (read == 0) break;
            }
        }
        String content = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        boolean truncated = startOffset > 0;
        if (truncated) {
            int firstLineBreak = content.indexOf('\n');
            content = firstLineBreak >= 0 ? content.substring(firstLineBreak + 1) : "";
        }
        return new TailContent(startOffset, content, truncated);
    }

    private List<RuntimeLogEntry> parse(TailContent tail) {
        List<RuntimeLogEntry> entries = new ArrayList<>();
        EntryBuilder current = null;
        String[] lines = tail.content().split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            Matcher matcher = LOG_HEADER.matcher(lines[index]);
            if (matcher.matches()) {
                if (current != null) entries.add(current.build());
                current = new EntryBuilder(
                        tail.startOffset() + index,
                        matcher.group("timestamp"),
                        matcher.group("level"),
                        matcher.group("thread"),
                        matcher.group("requestId"),
                        matcher.group("logger"),
                        matcher.group("message")
                );
            } else if (current != null) {
                current.appendDetail(lines[index]);
            }
        }
        if (current != null) entries.add(current.build());
        return entries;
    }

    private String normalizeLevel(String requestedLevel) {
        String level = requestedLevel == null || requestedLevel.isBlank()
                ? "ALL"
                : requestedLevel.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) throw new IllegalArgumentException("不支持的日志级别");
        return level;
    }

    private String normalizeKeyword(String requestedKeyword) {
        if (requestedKeyword == null) return "";
        String keyword = requestedKeyword.strip().toLowerCase(Locale.ROOT);
        return keyword.substring(0, Math.min(80, keyword.length()));
    }

    private String searchableText(RuntimeLogEntry entry) {
        return String.join(" ",
                entry.logger(),
                entry.requestId(),
                entry.message(),
                entry.details() == null ? "" : entry.details()
        ).toLowerCase(Locale.ROOT);
    }

    private Map<String, Long> emptyCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ERROR", 0L);
        counts.put("WARN", 0L);
        counts.put("INFO", 0L);
        counts.put("DEBUG", 0L);
        counts.put("TRACE", 0L);
        return counts;
    }

    private String readableMegabytes(int bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String explain(String level, String message, String details) {
        if (!"ERROR".equals(level) && !"WARN".equals(level)) return null;
        String text = (message + " " + (details == null ? "" : details)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "outofmemoryerror", "java heap space", "gc overhead")) {
            return "JVM 内存不足。请检查堆内存上限、近期并发量和大对象处理，必要时调整 JVM 内存参数。";
        }
        if (containsAny(text, "communications link failure", "sqlexception", "dataaccessexception", "hikari", "mysql")) {
            return "数据库连接或查询异常。请检查 MySQL 服务、连接地址、账号权限以及连接池状态。";
        }
        if (containsAny(text, "redis", "lettuce", "redisson")) {
            return "Redis 连接或命令执行异常。请检查 Redis 服务、密码和网络连接；部分在线状态能力可能降级。";
        }
        if (containsAny(text, "address already in use", "bindexception", "port is already")) {
            return "服务端口已被占用，当前节点可能无法启动。请停止占用进程或调整 server.port。";
        }
        if (containsAny(text, "accessdeniedexception", "authenticationexception", "unauthorized", "forbidden")) {
            return "请求未通过身份认证或权限校验。请结合请求 ID 检查登录状态、管理员权限和访问策略。";
        }
        if (containsAny(text, "jwt", "token expired", "signatureexception", "expiredjwtexception")) {
            return "访问令牌无效或已过期。请重新登录，并确认各节点使用一致且安全的 JWT 配置。";
        }
        if (containsAny(text, "websocket", "broken pipe", "closedchannel")) {
            return "实时连接发生异常。请检查客户端网络、反向代理 WebSocket 配置和允许的 Origin。";
        }
        if (containsAny(text, "nospaceleft", "no space left", "filesystem", "file system", "permission denied")) {
            return "文件系统不可用或空间不足。请检查日志/上传目录的剩余空间、挂载状态和读写权限。";
        }
        if (containsAny(text, "connection refused", "connectexception", "unknownhost", "timed out", "timeout", "unreachable")) {
            return "依赖服务或网络连接失败。请检查目标服务状态、地址、端口、防火墙和 DNS 配置。";
        }
        if ("ERROR".equals(level)) {
            return "服务执行过程中发生异常。请展开错误详情，并结合时间、请求 ID 和记录器定位对应操作。";
        }
        return "当前为警告，服务通常仍可运行；建议结合相邻日志确认是否持续发生或影响核心功能。";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    public record LogExport(Resource resource, String fileName) {
    }

    private record TailContent(long startOffset, String content, boolean truncated) {
    }

    private static final class EntryBuilder {
        private final long sequence;
        private final String timestamp;
        private final String level;
        private final String thread;
        private final String requestId;
        private final String logger;
        private final String message;
        private final StringBuilder details = new StringBuilder();

        private EntryBuilder(long sequence,
                             String timestamp,
                             String level,
                             String thread,
                             String requestId,
                             String logger,
                             String message) {
            this.sequence = sequence;
            this.timestamp = timestamp;
            this.level = level;
            this.thread = thread;
            this.requestId = requestId;
            this.logger = logger;
            this.message = truncate(message, MAX_MESSAGE_LENGTH);
        }

        private void appendDetail(String line) {
            if (details.length() >= MAX_DETAILS_LENGTH) return;
            if (!details.isEmpty()) details.append('\n');
            int remaining = MAX_DETAILS_LENGTH - details.length();
            details.append(line, 0, Math.min(line.length(), remaining));
        }

        private RuntimeLogEntry build() {
            String detailText = details.isEmpty() ? null : details.toString().stripTrailing();
            if (detailText != null && detailText.isEmpty()) detailText = null;
            return new RuntimeLogEntry(
                    sequence,
                    timestamp,
                    level,
                    thread,
                    requestId,
                    logger,
                    message,
                    detailText,
                    explain(level, message, detailText)
            );
        }

        private static String truncate(String value, int maxLength) {
            if (value == null || value.length() <= maxLength) return value == null ? "" : value;
            return value.substring(0, maxLength) + "…";
        }
    }
}
