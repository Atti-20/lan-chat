package com.lanchat.control.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.common.DeviceSessionsRevokedEvent;
import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.control.rbac.ControlAuthorizationMapper;
import com.lanchat.control.rbac.OrganizationMemberRow;
import com.lanchat.control.rbac.PermissionCode;
import com.lanchat.security.LoginUser;
import com.lanchat.security.UserContextHolder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;

@Service
public class DeviceManagementService {

    private static final Set<String> PLATFORMS = Set.of("DESKTOP", "ANDROID", "IOS");
    private static final int MAX_DEVICES = 200;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final ControlDeviceMapper deviceMapper;
    private final ControlAuthorizationMapper authorizationMapper;
    private final AuthorizationService authorizationService;
    private final ControlServerProperties controlProperties;
    private final DeviceSigningService signingService;
    private final ControlAuditService auditService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public DeviceManagementService(ControlDeviceMapper deviceMapper,
                                   ControlAuthorizationMapper authorizationMapper,
                                   AuthorizationService authorizationService,
                                   ControlServerProperties controlProperties,
                                   DeviceSigningService signingService,
                                   ControlAuditService auditService,
                                   ObjectMapper objectMapper,
                                   ApplicationEventPublisher eventPublisher) {
        this.deviceMapper = deviceMapper;
        this.authorizationMapper = authorizationMapper;
        this.authorizationService = authorizationService;
        this.controlProperties = controlProperties;
        this.signingService = signingService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DeviceView register(DeviceRegistrationRequest request) {
        signingService.requireEnabled();
        LoginUser loginUser = currentLogin();
        NormalizedRegistration normalized = normalize(request);
        OrganizationMemberRow member = activeMember(loginUser.getUserId());
        Long organizationId = requiredOrganizationId();
        DevicePolicyRecord policy = requiredPolicy();

        DeviceRecord device = deviceMapper.selectDeviceByKey(organizationKey(), normalized.deviceKey());
        if (device == null) {
            device = new DeviceRecord();
            device.setOrganizationId(organizationId);
            device.setMemberId(member.getMemberId());
            device.setOwnerUserId(loginUser.getUserId());
            device.setDeviceKey(normalized.deviceKey());
            device.setPlatform(normalized.platform());
            device.setDisplayName(normalized.displayName());
            device.setAppVersion(normalized.appVersion());
            device.setCapabilitiesJson(writeJson(normalized.capabilities()));
            device.setStatus("PENDING");
            if (deviceMapper.insertDevice(device) != 1 || device.getId() == null) {
                throw new IllegalStateException("设备目录写入失败");
            }
        } else {
            if (!loginUser.getUserId().equals(device.getOwnerUserId())) {
                throw new AccessDeniedException("该设备标识已属于其他组织成员");
            }
            if ("REVOKED".equals(device.getStatus())) {
                throw new AccessDeniedException("该设备已被吊销，不能重新注册");
            }
            deviceMapper.updateDeviceMetadata(
                    device.getId(), normalized.platform(), normalized.displayName(),
                    normalized.appVersion(), writeJson(normalized.capabilities()));
        }

        bindCurrentSession(device.getId(), loginUser);
        DeviceCredentialRecord credential = deviceMapper.selectUsableCredentialByFingerprint(
                device.getId(), normalized.fingerprint());
        if (credential == null) {
            deviceMapper.revokePendingCredentials(device.getId(), LocalDateTime.now());
            credential = new DeviceCredentialRecord();
            credential.setDeviceId(device.getId());
            credential.setCredentialId("cred_" + UUID.randomUUID().toString().replace("-", ""));
            credential.setAlgorithm("ED25519");
            credential.setPublicKey(normalized.publicKey());
            credential.setFingerprint(normalized.fingerprint());
            credential.setStatus("PENDING");
            credential.setRequestedAt(LocalDateTime.now());
            if (deviceMapper.insertCredential(credential) != 1 || credential.getId() == null) {
                throw new IllegalStateException("设备凭据申请写入失败");
            }
            auditService.appendRequired(
                    loginUser.getUserId(), "DEVICE_REGISTRATION_REQUESTED", "DEVICE",
                    String.valueOf(device.getId()), "SUCCEEDED",
                    Map.of("credentialId", credential.getCredentialId(),
                            "fingerprint", credential.getFingerprint(),
                            "platform", normalized.platform()));
        }

        if ("AUTO".equalsIgnoreCase(policy.getDeviceApprovalMode())
                && "PENDING".equals(credential.getStatus())) {
            credential = issueCredential(device, credential, policy, member.getMemberId(),
                    loginUser.getUserId());
        }
        return view(deviceMapper.selectDeviceById(organizationKey(), device.getId()), credential);
    }

    @Transactional
    public DeviceView approve(Long deviceId) {
        authorizationService.requireCurrentUserPermission(PermissionCode.DEVICE_APPROVE);
        Long actorUserId = currentUserId();
        OrganizationMemberRow actor = activeMember(actorUserId);
        DeviceRecord device = requiredDeviceForUpdate(deviceId);
        if ("REVOKED".equals(device.getStatus())) {
            throw new IllegalArgumentException("已吊销设备不能审批");
        }
        activeMember(device.getOwnerUserId());
        DeviceCredentialRecord credential = deviceMapper.selectPendingCredentialForUpdate(deviceId);
        if (credential == null) throw new IllegalArgumentException("设备没有待审批凭据");
        DeviceCredentialRecord issued = issueCredential(
                device, credential, requiredPolicy(), actor.getMemberId(), actorUserId);
        return view(deviceMapper.selectDeviceById(organizationKey(), deviceId), issued);
    }

    @Transactional
    public DeviceView reject(Long deviceId, DeviceRejectionRequest request) {
        authorizationService.requireCurrentUserPermission(PermissionCode.DEVICE_APPROVE);
        Long actorUserId = currentUserId();
        String reason = boundedRequired(request == null ? null : request.getReason(), 4, 200,
                "拒绝原因");
        DeviceRecord device = requiredDeviceForUpdate(deviceId);
        if ("REVOKED".equals(device.getStatus())) {
            throw new IllegalArgumentException("已吊销设备不能拒绝");
        }
        DeviceCredentialRecord credential = deviceMapper.selectPendingCredentialForUpdate(deviceId);
        if (credential == null) throw new IllegalArgumentException("设备没有待审批凭据");
        if (deviceMapper.revokeCredential(credential.getId(), LocalDateTime.now()) != 1) {
            throw new IllegalStateException("待审批凭据状态已经变化");
        }
        if (deviceMapper.countActiveCredentials(deviceId) > 0) {
            deviceMapper.recordRotationRejection(deviceId, reason);
        } else {
            deviceMapper.rejectPendingDevice(deviceId, reason);
        }
        auditService.appendRequired(actorUserId, "DEVICE_REGISTRATION_REJECTED", "DEVICE",
                String.valueOf(deviceId), "SUCCEEDED",
                Map.of("credentialId", credential.getCredentialId(), "reason", reason));
        return view(deviceMapper.selectDeviceById(organizationKey(), deviceId),
                deviceMapper.selectLatestCredential(deviceId));
    }

    @Transactional
    public DeviceView revoke(Long deviceId, DeviceRevocationRequest request) {
        authorizationService.requireCurrentUserPermission(PermissionCode.DEVICE_REVOKE);
        String expectedPhrase = "REVOKE DEVICE " + deviceId;
        if (request == null || !expectedPhrase.equals(request.getConfirmationPhrase())) {
            throw new IllegalArgumentException("确认短语不匹配，应为：" + expectedPhrase);
        }
        String reason = boundedRequired(request.getReason(), 4, 200, "吊销原因");
        Long actorUserId = currentUserId();
        List<Long> legacySessionIds = new ArrayList<>();
        Long ownerUserId = revokeInternal(
                deviceId, actorUserId, reason, "DEVICE_REVOKED", legacySessionIds);
        publishSessionRevocation(
                legacySessionIds, ownerUserId, "DEVICE_REVOKED", "设备已被管理员吊销");
        return view(deviceMapper.selectDeviceById(organizationKey(), deviceId),
                deviceMapper.selectLatestCredential(deviceId));
    }

    @Transactional
    public int revokeAllForUser(Long userId, Long actorUserId, String reason) {
        if (userId == null || userId <= 0) return 0;
        List<Long> deviceIds = deviceMapper.selectRevocableDeviceIdsByUser(organizationKey(), userId);
        if (deviceIds == null || deviceIds.isEmpty()) return 0;
        signingService.requireEnabled();
        List<Long> legacySessionIds = new ArrayList<>();
        for (Long deviceId : deviceIds) {
            revokeInternal(deviceId, actorUserId,
                    boundedRequired(reason, 4, 200, "吊销原因"),
                    "ACCOUNT_DEVICE_REVOKED", legacySessionIds);
        }
        publishSessionRevocation(legacySessionIds, userId,
                "ACCOUNT_REVOKED", "账号状态变化，设备会话已经失效");
        return deviceIds.size();
    }

    public List<DeviceView> list(int requestedLimit) {
        Long userId = currentUserId();
        if (!authorizationService.hasPermission(userId, PermissionCode.DEVICE_APPROVE)
                && !authorizationService.hasPermission(userId, PermissionCode.DEVICE_REVOKE)) {
            throw new AccessDeniedException("当前账号缺少设备管理权限");
        }
        int limit = Math.max(1, Math.min(requestedLimit, MAX_DEVICES));
        List<DeviceRecord> devices = deviceMapper.selectDevices(organizationKey(), limit);
        if (devices == null) return List.of();
        return devices.stream()
                .map(device -> view(device, deviceMapper.selectLatestCredential(device.getId())))
                .toList();
    }

    public RevocationSnapshotView revocations(long afterVersion, int requestedLimit) {
        signingService.requireEnabled();
        currentLogin();
        long normalizedVersion = Math.max(0, afterVersion);
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        List<RevocationEntryRecord> rows = deviceMapper.selectRevocations(
                organizationKey(), normalizedVersion, limit);
        // Read the monotonic version after the page. A concurrent revoke can
        // then only make currentVersion newer than the returned entries, never
        // older than an entry the client has already received.
        DevicePolicyRecord policy = requiredPolicy();
        List<RevocationEntryView> entries = rows == null ? List.of() : rows.stream()
                .map(row -> new RevocationEntryView(
                        row.getVersion(), row.getSubjectType(), row.getSubjectId(), row.getReason(),
                        row.getRevokedAt(), row.getExpiresAt(), row.getSignedPayload(),
                        row.getSignature(), row.getControlKeyFingerprint()))
                .toList();
        return new RevocationSnapshotView(
                organizationKey(), safeVersion(policy.getRevocationVersion()), normalizedVersion,
                signingService.signingPublicKey(), signingService.signingKeyFingerprint(), entries);
    }

    public ControlSessionView currentSession() {
        LoginUser loginUser = currentLogin();
        OrganizationMemberRow member = activeMember(loginUser.getUserId());
        DeviceSessionRecord session = deviceMapper.selectSessionByAccessHash(
                sha256Hex(loginUser.getToken()));
        DeviceView device = null;
        if (session != null && "ACTIVE".equals(session.getStatus())
                && loginUser.getUserId().equals(session.getUserId())) {
            DeviceRecord record = deviceMapper.selectDeviceById(organizationKey(), session.getDeviceId());
            if (record != null) {
                device = view(record, deviceMapper.selectLatestCredential(record.getId()));
            }
        }
        List<String> roles = authorizationMapper.selectRoleCodes(organizationKey(), loginUser.getUserId());
        List<PermissionCode> permissions = authorizationService.permissionCodes(loginUser.getUserId())
                .stream().sorted(Comparator.comparing(Enum::name)).toList();
        DevicePolicyRecord policy = requiredPolicy();
        return new ControlSessionView(
                organizationKey(), loginUser.getUserId(), member.getMemberId(), member.getStatus(),
                roles == null ? List.of() : List.copyOf(roles), permissions, device,
                safeVersion(policy.getRevocationVersion()));
    }

    private DeviceCredentialRecord issueCredential(DeviceRecord device,
                                                    DeviceCredentialRecord credential,
                                                    DevicePolicyRecord policy,
                                                    Long approverMemberId,
                                                    Long actorUserId) {
        LocalDateTime issuedAt = LocalDateTime.now();
        int validityDays = policy.getCredentialValidityDays() == null
                ? 90 : Math.max(1, Math.min(policy.getCredentialValidityDays(), 3650));
        int maxOfflineHours = policy.getMaxOfflineHours() == null
                ? 72 : Math.max(1, Math.min(policy.getMaxOfflineHours(), 8760));
        LocalDateTime expiresAt = issuedAt.plusDays(validityDays);

        LinkedHashMap<String, Object> payloadValues = new LinkedHashMap<>();
        payloadValues.put("type", "MESHX_DEVICE_CERTIFICATE");
        payloadValues.put("version", 1);
        payloadValues.put("credentialId", credential.getCredentialId());
        payloadValues.put("organizationId", organizationKey());
        payloadValues.put("controlId", controlProperties.resolvedId());
        payloadValues.put("deviceId", device.getId());
        payloadValues.put("deviceKey", device.getDeviceKey());
        payloadValues.put("memberId", device.getMemberId());
        payloadValues.put("algorithm", credential.getAlgorithm());
        payloadValues.put("publicKeyFingerprint", credential.getFingerprint());
        payloadValues.put("issuedAt", timestamp(issuedAt));
        payloadValues.put("expiresAt", timestamp(expiresAt));
        payloadValues.put("maxOfflineHours", maxOfflineHours);
        String payload = writeJson(payloadValues);
        String signature = signingService.sign(payload);
        if (deviceMapper.activateCredential(
                credential.getId(), payload, signature, signingService.signingKeyFingerprint(),
                approverMemberId, issuedAt, expiresAt) != 1) {
            throw new IllegalStateException("待审批凭据状态已经变化");
        }
        deviceMapper.revokeOtherCredentials(device.getId(), credential.getId(), issuedAt);
        if (deviceMapper.approveDevice(device.getId(), approverMemberId, issuedAt) != 1) {
            throw new IllegalStateException("设备审批状态更新失败");
        }
        credential.setStatus("ACTIVE");
        credential.setCertificatePayload(payload);
        credential.setCertificateSignature(signature);
        credential.setControlKeyFingerprint(signingService.signingKeyFingerprint());
        credential.setApprovedBy(approverMemberId);
        credential.setIssuedAt(issuedAt);
        credential.setExpiresAt(expiresAt);
        auditService.appendRequired(actorUserId, "DEVICE_APPROVED", "DEVICE",
                String.valueOf(device.getId()), "SUCCEEDED",
                Map.of("credentialId", credential.getCredentialId(),
                        "fingerprint", credential.getFingerprint(),
                        "expiresAt", timestamp(expiresAt)));
        return credential;
    }

    private Long revokeInternal(Long deviceId,
                                Long actorUserId,
                                String reason,
                                String auditAction,
                                List<Long> legacySessionIds) {
        signingService.requireEnabled();
        DeviceRecord device = requiredDeviceForUpdate(deviceId);
        if ("REVOKED".equals(device.getStatus())) {
            throw new IllegalArgumentException("设备已经被吊销");
        }
        DevicePolicyRecord policy = deviceMapper.lockPolicy(organizationKey());
        if (policy == null) throw new IllegalStateException("组织设备策略未初始化");
        long previousVersion = safeVersion(policy.getRevocationVersion());
        long version = previousVersion + 1;
        if (deviceMapper.updateRevocationVersion(
                policy.getId(), previousVersion, version, actorUserId) != 1) {
            throw new IllegalStateException("吊销目录版本冲突，请重试");
        }

        LocalDateTime revokedAt = LocalDateTime.now();
        LinkedHashMap<String, Object> payloadValues = new LinkedHashMap<>();
        payloadValues.put("type", "MESHX_REVOCATION");
        payloadValues.put("version", version);
        payloadValues.put("organizationId", organizationKey());
        payloadValues.put("controlId", controlProperties.resolvedId());
        payloadValues.put("subjectType", "DEVICE");
        payloadValues.put("subjectId", String.valueOf(deviceId));
        payloadValues.put("deviceKey", device.getDeviceKey());
        payloadValues.put("reason", reason);
        payloadValues.put("revokedAt", timestamp(revokedAt));
        String payload = writeJson(payloadValues);

        RevocationEntryRecord entry = new RevocationEntryRecord();
        entry.setOrganizationId(device.getOrganizationId());
        entry.setSubjectType("DEVICE");
        entry.setSubjectId(String.valueOf(deviceId));
        entry.setVersion(version);
        entry.setReason(reason);
        entry.setSignedPayload(payload);
        entry.setSignature(signingService.sign(payload));
        entry.setControlKeyFingerprint(signingService.signingKeyFingerprint());
        entry.setRevokedBy(actorUserId);
        entry.setRevokedAt(revokedAt);
        if (deviceMapper.insertRevocation(entry) != 1) {
            throw new IllegalStateException("吊销目录写入失败");
        }

        List<Long> activeLegacyIds = deviceMapper.selectActiveLegacySessionIds(deviceId);
        if (activeLegacyIds != null) legacySessionIds.addAll(activeLegacyIds);
        deviceMapper.revokeAllCredentials(deviceId, revokedAt);
        deviceMapper.revokeLegacySessions(deviceId);
        deviceMapper.revokeDeviceSessions(deviceId, revokedAt);
        if (deviceMapper.revokeDevice(deviceId, actorUserId, revokedAt) != 1) {
            throw new IllegalStateException("设备吊销状态更新失败");
        }
        auditService.appendRequired(actorUserId, auditAction, "DEVICE", String.valueOf(deviceId),
                "SUCCEEDED", Map.of("reason", reason, "revocationVersion", version));
        return device.getOwnerUserId();
    }

    private void bindCurrentSession(Long deviceId, LoginUser loginUser) {
        if (!StringUtils.hasText(loginUser.getToken())) {
            throw new AccessDeniedException("当前登录会话无法绑定设备");
        }
        String accessHash = sha256Hex(loginUser.getToken());
        DeviceSessionRecord existing = deviceMapper.selectSessionByAccessHash(accessHash);
        if (existing != null) {
            if (!deviceId.equals(existing.getDeviceId())
                    || !loginUser.getUserId().equals(existing.getUserId())
                    || !"ACTIVE".equals(existing.getStatus())) {
                throw new AccessDeniedException("当前登录会话已绑定其他设备或已失效");
            }
            return;
        }
        if (deviceMapper.insertSessionBinding(
                organizationKey(), deviceId, loginUser.getUserId(), loginUser.getToken(),
                accessHash) != 1) {
            throw new AccessDeniedException("当前登录会话不存在或已经失效");
        }
    }

    private DeviceView view(DeviceRecord device, DeviceCredentialRecord credential) {
        if (device == null) return null;
        DeviceCredentialView credentialView = null;
        if (credential != null) {
            credentialView = new DeviceCredentialView(
                    credential.getCredentialId(), credential.getAlgorithm(),
                    credential.getFingerprint(), credential.getStatus(),
                    credential.getRequestedAt(), credential.getIssuedAt(), credential.getExpiresAt(),
                    credential.getCertificatePayload(), credential.getCertificateSignature(),
                    signingService.isEnabled() ? signingService.signingPublicKey() : null,
                    credential.getControlKeyFingerprint());
        }
        return new DeviceView(
                device.getId(), device.getOwnerUserId(), device.getDeviceKey(), device.getPlatform(),
                device.getDisplayName(), device.getAppVersion(), readCapabilities(device.getCapabilitiesJson()),
                device.getStatus(), device.getApprovedAt(), device.getRejectionReason(),
                device.getRevokedAt(), device.getLastSeenAt(), credentialView);
    }

    private NormalizedRegistration normalize(DeviceRegistrationRequest request) {
        if (request == null) throw new IllegalArgumentException("设备注册参数不能为空");
        String deviceKey = boundedRequired(request.getDeviceKey(), 8, 100, "设备标识");
        if (!deviceKey.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{7,99}$")) {
            throw new IllegalArgumentException("设备标识格式无效");
        }
        String platform = boundedRequired(request.getPlatform(), 2, 30, "设备平台")
                .toUpperCase(Locale.ROOT);
        if (!PLATFORMS.contains(platform)) throw new IllegalArgumentException("设备平台无效");
        String displayName = bounded(request.getDisplayName(), 100);
        String appVersion = bounded(request.getAppVersion(), 50);
        List<String> capabilities = normalizeCapabilities(request.getCapabilities());
        String publicKey = signingService.validateAndNormalizeClientPublicKey(
                request.getAlgorithm(), request.getPublicKey());
        return new NormalizedRegistration(
                deviceKey, platform, displayName, appVersion, capabilities,
                publicKey, signingService.fingerprint(publicKey));
    }

    private List<String> normalizeCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) return List.of();
        if (capabilities.size() > 32) throw new IllegalArgumentException("设备能力数量过多");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String capability : capabilities) {
            if (!StringUtils.hasText(capability)) continue;
            String value = capability.trim().toUpperCase(Locale.ROOT);
            if (!value.matches("^[A-Z][A-Z0-9_]{1,39}$")) {
                throw new IllegalArgumentException("设备能力格式无效：" + capability);
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private List<String> readCapabilities(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return values == null ? List.of() : List.copyOf(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("设备能力数据损坏", exception);
        }
    }

    private OrganizationMemberRow activeMember(Long userId) {
        OrganizationMemberRow member = authorizationMapper.selectMember(organizationKey(), userId);
        if (member == null || !"ACTIVE".equals(member.getStatus())) {
            throw new AccessDeniedException("当前账号不是有效组织成员");
        }
        return member;
    }

    private DeviceRecord requiredDeviceForUpdate(Long deviceId) {
        if (deviceId == null || deviceId <= 0) throw new IllegalArgumentException("设备标识无效");
        DeviceRecord device = deviceMapper.selectDeviceForUpdate(organizationKey(), deviceId);
        if (device == null) throw new IllegalArgumentException("设备不存在");
        return device;
    }

    private DevicePolicyRecord requiredPolicy() {
        DevicePolicyRecord policy = deviceMapper.selectPolicy(organizationKey());
        if (policy == null) throw new IllegalStateException("组织设备策略未初始化");
        return policy;
    }

    private Long requiredOrganizationId() {
        Long organizationId = deviceMapper.selectOrganizationId(organizationKey());
        if (organizationId == null) throw new IllegalStateException("组织未初始化");
        return organizationId;
    }

    private LoginUser currentLogin() {
        LoginUser user = UserContextHolder.getCurrentUser();
        if (user == null || user.getUserId() == null) throw new AccessDeniedException("请先登录");
        return user;
    }

    private Long currentUserId() {
        return currentLogin().getUserId();
    }

    private String organizationKey() {
        return controlProperties.resolvedOrganizationId();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("设备数据无法序列化", exception);
        }
    }

    private String boundedRequired(String value, int minLength, int maxLength, String label) {
        String normalized = bounded(value, maxLength);
        if (normalized.length() < minLength) {
            throw new IllegalArgumentException(label + "长度需为" + minLength + "-" + maxLength + "个字符");
        }
        return normalized;
    }

    private String bounded(String value, int maxLength) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("字段内容过长");
        return normalized;
    }

    private String sha256Hex(String value) {
        if (!StringUtils.hasText(value)) throw new AccessDeniedException("当前登录会话无效");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String timestamp(LocalDateTime value) {
        return value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private long safeVersion(Long version) {
        return version == null ? 0 : Math.max(0, version);
    }

    private void publishSessionRevocation(List<Long> legacySessionIds,
                                          Long userId,
                                          String reason,
                                          String message) {
        if (legacySessionIds == null || legacySessionIds.isEmpty()) return;
        eventPublisher.publishEvent(new DeviceSessionsRevokedEvent(
                userId, legacySessionIds.stream().distinct().toList(), reason, message));
    }

    private record NormalizedRegistration(
            String deviceKey,
            String platform,
            String displayName,
            String appVersion,
            List<String> capabilities,
            String publicKey,
            String fingerprint
    ) { }
}
