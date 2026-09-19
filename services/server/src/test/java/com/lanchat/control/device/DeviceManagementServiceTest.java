package com.lanchat.control.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.common.DeviceSessionsRevokedEvent;
import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.control.rbac.ControlAuthorizationMapper;
import com.lanchat.control.rbac.OrganizationMemberRow;
import com.lanchat.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceManagementServiceTest {

    private ControlDeviceMapper deviceMapper;
    private ControlAuthorizationMapper authorizationMapper;
    private AuthorizationService authorizationService;
    private DeviceSigningService signingService;
    private ControlAuditService auditService;
    private ApplicationEventPublisher eventPublisher;
    private DeviceManagementService service;

    @BeforeEach
    void setUp() {
        deviceMapper = mock(ControlDeviceMapper.class);
        authorizationMapper = mock(ControlAuthorizationMapper.class);
        authorizationService = mock(AuthorizationService.class);
        signingService = mock(DeviceSigningService.class);
        auditService = mock(ControlAuditService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        ControlServerProperties control = new ControlServerProperties();
        control.setId("control-test-01");
        control.setOrganizationId("org-test");
        service = new DeviceManagementService(
                deviceMapper, authorizationMapper, authorizationService, control,
                signingService, auditService, new ObjectMapper(), eventPublisher);
        LoginUser actor = new LoginUser(7L, "member.account", "web", "access-token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        when(signingService.isEnabled()).thenReturn(true);
        when(signingService.signingPublicKey()).thenReturn("control-public-key");
        when(signingService.signingKeyFingerprint()).thenReturn("control-fingerprint");
        when(authorizationMapper.selectMember("org-test", 7L)).thenReturn(member(11L, 7L));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void manualRegistrationStoresOnlyPublicCredentialAndBindsHashedSession() {
        DeviceRegistrationRequest request = registration();
        when(signingService.validateAndNormalizeClientPublicKey("ED25519", "device-public-key"))
                .thenReturn("normalized-public-key");
        when(signingService.fingerprint("normalized-public-key")).thenReturn("device-fingerprint");
        when(deviceMapper.selectOrganizationId("org-test")).thenReturn(1L);
        when(deviceMapper.selectPolicy("org-test")).thenReturn(policy("MANUAL", 0L));
        AtomicReference<DeviceRecord> insertedDevice = new AtomicReference<>();
        when(deviceMapper.insertDevice(any(DeviceRecord.class))).thenAnswer(invocation -> {
            DeviceRecord device = invocation.getArgument(0);
            device.setId(5L);
            device.setOwnerUserId(7L);
            insertedDevice.set(device);
            return 1;
        });
        when(deviceMapper.insertSessionBinding(
                eq("org-test"), eq(5L), eq(7L), eq("access-token"), anyString()))
                .thenReturn(1);
        AtomicReference<DeviceCredentialRecord> insertedCredential = new AtomicReference<>();
        when(deviceMapper.insertCredential(any(DeviceCredentialRecord.class))).thenAnswer(invocation -> {
            DeviceCredentialRecord credential = invocation.getArgument(0);
            credential.setId(8L);
            insertedCredential.set(credential);
            return 1;
        });
        when(deviceMapper.selectDeviceById("org-test", 5L))
                .thenAnswer(invocation -> insertedDevice.get());

        DeviceView result = service.register(request);

        assertEquals("PENDING", result.status());
        assertEquals("PENDING", result.credential().status());
        assertEquals("device-fingerprint", result.credential().fingerprint());
        assertEquals("normalized-public-key", insertedCredential.get().getPublicKey());
        verify(deviceMapper).insertSessionBinding(
                eq("org-test"), eq(5L), eq(7L), eq("access-token"),
                org.mockito.ArgumentMatchers.matches("^[0-9a-f]{64}$"));
        verify(deviceMapper, never()).activateCredential(
                anyLong(), anyString(), anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void aSessionCannotBeReboundToAnotherDevice() {
        DeviceRegistrationRequest request = registration();
        when(signingService.validateAndNormalizeClientPublicKey(anyString(), anyString()))
                .thenReturn("normalized-public-key");
        when(signingService.fingerprint(anyString())).thenReturn("device-fingerprint");
        when(deviceMapper.selectOrganizationId("org-test")).thenReturn(1L);
        when(deviceMapper.selectPolicy("org-test")).thenReturn(policy("MANUAL", 0L));
        DeviceRecord existing = device(5L, "PENDING");
        when(deviceMapper.selectDeviceByKey("org-test", "device-0001")).thenReturn(existing);
        DeviceSessionRecord boundElsewhere = new DeviceSessionRecord();
        boundElsewhere.setDeviceId(99L);
        boundElsewhere.setUserId(7L);
        boundElsewhere.setStatus("ACTIVE");
        when(deviceMapper.selectSessionByAccessHash(anyString())).thenReturn(boundElsewhere);

        assertThrows(AccessDeniedException.class, () -> service.register(request));

        verify(deviceMapper, never()).insertCredential(any());
    }

    @Test
    void automaticPolicyIssuesCredentialDuringRegistration() {
        DeviceRegistrationRequest request = registration();
        when(signingService.validateAndNormalizeClientPublicKey("ED25519", "device-public-key"))
                .thenReturn("normalized-public-key");
        when(signingService.fingerprint("normalized-public-key")).thenReturn("device-fingerprint");
        when(signingService.sign(anyString())).thenReturn("signed-certificate");
        when(deviceMapper.selectOrganizationId("org-test")).thenReturn(1L);
        when(deviceMapper.selectPolicy("org-test")).thenReturn(policy("AUTO", 0L));
        when(deviceMapper.insertDevice(any(DeviceRecord.class))).thenAnswer(invocation -> {
            DeviceRecord device = invocation.getArgument(0);
            device.setId(5L);
            device.setOwnerUserId(7L);
            return 1;
        });
        when(deviceMapper.insertSessionBinding(
                eq("org-test"), eq(5L), eq(7L), eq("access-token"), anyString()))
                .thenReturn(1);
        when(deviceMapper.insertCredential(any(DeviceCredentialRecord.class))).thenAnswer(invocation -> {
            DeviceCredentialRecord credential = invocation.getArgument(0);
            credential.setId(8L);
            return 1;
        });
        when(deviceMapper.activateCredential(eq(8L), anyString(), eq("signed-certificate"),
                eq("control-fingerprint"), eq(11L), any(), any())).thenReturn(1);
        when(deviceMapper.approveDevice(eq(5L), eq(11L), any())).thenReturn(1);
        when(deviceMapper.selectDeviceById("org-test", 5L)).thenReturn(device(5L, "ACTIVE"));

        DeviceView result = service.register(request);

        assertEquals("ACTIVE", result.status());
        assertEquals("ACTIVE", result.credential().status());
        verify(deviceMapper).activateCredential(
                eq(8L), anyString(), eq("signed-certificate"),
                eq("control-fingerprint"), eq(11L), any(), any());
    }

    @Test
    void approvalIssuesSignedCertificateAndRotatesPreviousCredential() {
        DeviceRecord pending = device(5L, "PENDING");
        DeviceCredentialRecord credential = credential(8L, "PENDING");
        when(deviceMapper.selectDeviceForUpdate("org-test", 5L)).thenReturn(pending);
        when(deviceMapper.selectPendingCredentialForUpdate(5L)).thenReturn(credential);
        when(deviceMapper.selectPolicy("org-test")).thenReturn(policy("MANUAL", 2L));
        when(signingService.sign(anyString())).thenReturn("signed-certificate");
        when(deviceMapper.activateCredential(eq(8L), anyString(), eq("signed-certificate"),
                eq("control-fingerprint"), eq(11L), any(), any())).thenReturn(1);
        when(deviceMapper.approveDevice(eq(5L), eq(11L), any())).thenReturn(1);
        DeviceRecord active = device(5L, "ACTIVE");
        when(deviceMapper.selectDeviceById("org-test", 5L)).thenReturn(active);

        DeviceView result = service.approve(5L);

        assertEquals("ACTIVE", result.credential().status());
        assertEquals("signed-certificate", result.credential().certificateSignature());
        assertNotNull(result.credential().certificatePayload());
        verify(deviceMapper).revokeOtherCredentials(eq(5L), eq(8L), any());
        verify(auditService).appendRequired(
                eq(7L), eq("DEVICE_APPROVED"), eq("DEVICE"), eq("5"),
                eq("SUCCEEDED"), any());
    }

    @Test
    void revocationAdvancesSignedVersionAndInvalidatesBothSessionStores() {
        DeviceRecord active = device(5L, "ACTIVE");
        DeviceRecord revoked = device(5L, "REVOKED");
        DevicePolicyRecord policy = policy("MANUAL", 3L);
        policy.setId(4L);
        when(deviceMapper.selectDeviceForUpdate("org-test", 5L)).thenReturn(active);
        when(deviceMapper.lockPolicy("org-test")).thenReturn(policy);
        when(deviceMapper.updateRevocationVersion(4L, 3L, 4L, 7L)).thenReturn(1);
        when(signingService.sign(anyString())).thenReturn("signed-revocation");
        when(deviceMapper.insertRevocation(any(RevocationEntryRecord.class))).thenReturn(1);
        when(deviceMapper.selectActiveLegacySessionIds(5L)).thenReturn(List.of(91L, 92L));
        when(deviceMapper.revokeDevice(eq(5L), eq(7L), any())).thenReturn(1);
        when(deviceMapper.selectDeviceById("org-test", 5L)).thenReturn(revoked);
        DeviceRevocationRequest request = new DeviceRevocationRequest();
        request.setConfirmationPhrase("REVOKE DEVICE 5");
        request.setReason("LOST_DEVICE");

        DeviceView result = service.revoke(5L, request);

        assertEquals("REVOKED", result.status());
        ArgumentCaptor<RevocationEntryRecord> entryCaptor =
                ArgumentCaptor.forClass(RevocationEntryRecord.class);
        verify(deviceMapper).insertRevocation(entryCaptor.capture());
        assertEquals(4L, entryCaptor.getValue().getVersion());
        assertEquals("signed-revocation", entryCaptor.getValue().getSignature());
        verify(deviceMapper).revokeAllCredentials(eq(5L), any());
        verify(deviceMapper).revokeLegacySessions(5L);
        verify(deviceMapper).revokeDeviceSessions(eq(5L), any());
        ArgumentCaptor<DeviceSessionsRevokedEvent> eventCaptor =
                ArgumentCaptor.forClass(DeviceSessionsRevokedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(7L, eventCaptor.getValue().userId());
        assertEquals(List.of(91L, 92L), eventCaptor.getValue().deviceIds());
    }

    private DeviceRegistrationRequest registration() {
        DeviceRegistrationRequest request = new DeviceRegistrationRequest();
        request.setDeviceKey("device-0001");
        request.setPlatform("desktop");
        request.setDisplayName("Office Mac");
        request.setAppVersion("0.3.0");
        request.setCapabilities(List.of("chat", "file_transfer"));
        request.setAlgorithm("ED25519");
        request.setPublicKey("device-public-key");
        return request;
    }

    private OrganizationMemberRow member(Long memberId, Long userId) {
        OrganizationMemberRow member = new OrganizationMemberRow();
        member.setMemberId(memberId);
        member.setUserId(userId);
        member.setStatus("ACTIVE");
        return member;
    }

    private DevicePolicyRecord policy(String approvalMode, Long revocationVersion) {
        DevicePolicyRecord policy = new DevicePolicyRecord();
        policy.setId(4L);
        policy.setOrganizationId(1L);
        policy.setDeviceApprovalMode(approvalMode);
        policy.setCredentialValidityDays(90);
        policy.setMaxOfflineHours(72);
        policy.setRevocationVersion(revocationVersion);
        return policy;
    }

    private DeviceRecord device(Long id, String status) {
        DeviceRecord device = new DeviceRecord();
        device.setId(id);
        device.setOrganizationId(1L);
        device.setMemberId(11L);
        device.setOwnerUserId(7L);
        device.setDeviceKey("device-0001");
        device.setPlatform("DESKTOP");
        device.setDisplayName("Office Mac");
        device.setAppVersion("0.3.0");
        device.setCapabilitiesJson("[]");
        device.setStatus(status);
        return device;
    }

    private DeviceCredentialRecord credential(Long id, String status) {
        DeviceCredentialRecord credential = new DeviceCredentialRecord();
        credential.setId(id);
        credential.setDeviceId(5L);
        credential.setCredentialId("cred_0001");
        credential.setAlgorithm("ED25519");
        credential.setPublicKey("device-public-key");
        credential.setFingerprint("device-fingerprint");
        credential.setStatus(status);
        credential.setRequestedAt(java.time.LocalDateTime.now());
        return credential;
    }
}
