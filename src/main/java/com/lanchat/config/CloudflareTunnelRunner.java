package com.lanchat.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Slf4j
@Component
public class CloudflareTunnelRunner implements ApplicationRunner {

    @Value("${tunnel.enabled:false}")
    private boolean tunnelEnabled;

    @Value("${tunnel.hostname:}")
    private String hostname;

    private Process tunnelProcess;
    private Thread tunnelOutputThread;

    @Override
    public void run(ApplicationArguments args) {
        if (!tunnelEnabled) {
            log.info("Cloudflare Tunnel 已禁用 (tunnel.enabled=false)");
            return;
        }

        String tunnelId = System.getenv("CLOUDFLARE_TUNNEL_ID");
        if (tunnelId == null || tunnelId.isBlank()) {
            tunnelId = "lan-chat";
        }

        String credentialsFile = System.getenv("CLOUDFLARE_CREDENTIALS_FILE");
        if (credentialsFile == null || credentialsFile.isBlank()) {
            String home = System.getProperty("user.home");
            credentialsFile = home + "/.cloudflared/adcb40f9-2e2b-4fea-a820-c22118a4e307.json";
        }

        String configPath = System.getProperty("user.dir") + "/.cloudflared/config.yml";
        if (!new File(configPath).exists()) {
            // 写入临时配置
            configPath = writeTempConfig(tunnelId, credentialsFile);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "cloudflared", "tunnel", "--config", configPath, "run"
            );
            // Route child-process output through SLF4J so console and rolling-file logs stay identical.
            pb.redirectErrorStream(true);
            tunnelProcess = pb.start();
            startOutputPump(tunnelProcess);

            log.info("Cloudflare Tunnel 已启动 → https://{}", hostname);
        } catch (IOException e) {
            log.error("Cloudflare Tunnel 启动失败", e);
        }
    }

    private void startOutputPump(Process process) {
        tunnelOutputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logTunnelLine(line);
                }
            } catch (IOException exception) {
                if (process.isAlive()) log.warn("cloudflared 输出读取失败: {}", exception.getMessage());
            }
        }, "cloudflared-output");
        tunnelOutputThread.setDaemon(true);
        tunnelOutputThread.start();
    }

    private void logTunnelLine(String rawLine) {
        String line = rawLine.replaceAll("[\\p{Cntrl}&&[^\\t]]", "");
        line = line.substring(0, Math.min(2_000, line.length()));
        String normalized = line.toLowerCase(Locale.ROOT);
        if (normalized.contains("level=error") || normalized.contains("\"level\":\"error\"")) {
            log.error("cloudflared | {}", line);
        } else if (normalized.contains("level=warn") || normalized.contains("\"level\":\"warn\"")) {
            log.warn("cloudflared | {}", line);
        } else {
            log.info("cloudflared | {}", line);
        }
    }

    private String writeTempConfig(String tunnelId, String credentialsFile) {
        String content = String.format("""
                tunnel: %s
                credentials-file: %s
                ingress:
                  - hostname: %s
                    service: http://localhost:8080
                  - service: http_status:404
                """, tunnelId, credentialsFile, hostname);

        File tempFile;
        try {
            tempFile = File.createTempFile("cloudflared-", ".yml");
            java.nio.file.Files.writeString(tempFile.toPath(), content);
            tempFile.deleteOnExit();
            log.info("已生成临时隧道配置: {}", tempFile.getAbsolutePath());
            return tempFile.getAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException("无法写入临时隧道配置", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (tunnelProcess != null && tunnelProcess.isAlive()) {
            tunnelProcess.destroy();
            log.info("Cloudflare Tunnel 已停止");
        }
        if (tunnelOutputThread != null) tunnelOutputThread.interrupt();
    }
}
