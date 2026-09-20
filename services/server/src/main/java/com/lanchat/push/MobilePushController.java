package com.lanchat.push;
import com.lanchat.common.Result;
import com.lanchat.security.UserContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/push")
public class MobilePushController {
    private final MobilePushService service;
    private final PushConfiguration config;
    public MobilePushController(MobilePushService service, PushConfiguration config) { this.service = service; this.config = config; }
    @GetMapping("/config")
    public Result<Map<String, Object>> mobilePushConfiguration() { return Result.success(Map.of("enabled", config.isEnabled(), "apns", config.apnsReady(), "fcm", config.fcmReady(), "firebase", Map.of("projectId", config.getFcmProjectId(), "applicationId", config.getFcmApplicationId(), "apiKey", config.getFcmApiKey(), "senderId", config.getFcmSenderId()))); }
    @PutMapping("/subscription")
    public Result<Void> registerDevicePush(@RequestBody PushSubscriptionRequest request) { service.register(UserContextHolder.getCurrentUser(), request); return Result.success(); }
    @DeleteMapping("/subscription")
    public Result<Void> unregisterDevicePush() { service.unregister(UserContextHolder.getCurrentUser()); return Result.success(); }
}
