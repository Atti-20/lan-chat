package com.lanchat.control.service;

import com.lanchat.config.LanChatNodeProperties;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.control.device.DeviceSigningService;
import com.lanchat.control.protocol.ControlProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlServerInfoServiceTest {

    @Test
    void exposesOnlySanitizedControlMetadata() {
        ControlServerProperties control = new ControlServerProperties();
        control.setId("CONTROL_OFFICE_01");
        control.setOrganizationId("ORG_EXAMPLE");

        LanChatNodeProperties node = new LanChatNodeProperties();
        node.setOrganizationName("Example\nOrganization");
        node.setVersion("0.3.0");
        node.setSecure(true);

        ControlServerInfoService service = new ControlServerInfoService(
                control, node, mock(DeviceSigningService.class));
        var info = service.publicInfo();

        assertEquals("control_office_01", info.controlId());
        assertEquals("org_example", info.organizationId());
        assertEquals("ExampleOrganization", info.organizationName());
        assertEquals(ControlProtocol.PROTOCOL_VERSION, info.protocolVersion());
        assertEquals(ControlProtocol.API_BASE_PATH, info.apiBasePath());
        assertEquals(ControlProtocol.HEALTH_PATH, info.healthPath());
        assertEquals("/api/v1", info.legacyApiBasePath());
        assertEquals("PASSWORD", info.authMethods().get(0));
        assertTrue(info.secure());
        assertTrue(info.features().contains("CHAT"));
        assertFalse(info.toString().contains("password"));
    }

    @Test
    void healthUsesTheSeparatedControlIdentity() {
        ControlServerProperties control = new ControlServerProperties();
        control.setId("control-office-01");
        LanChatNodeProperties node = new LanChatNodeProperties();

        var health = new ControlServerInfoService(
                control, node, mock(DeviceSigningService.class)).publicHealth();

        assertEquals("UP", health.status());
        assertEquals("control-office-01", health.controlId());
        assertEquals(ControlProtocol.PROTOCOL_VERSION, health.protocolVersion());
        assertTrue(health.uptimeSeconds() >= 0);
    }

    @Test
    void enabledDeviceIdentityPublishesOnlyTheControlPublicTrustAnchor() {
        ControlServerProperties control = new ControlServerProperties();
        LanChatNodeProperties node = new LanChatNodeProperties();
        DeviceSigningService signing = mock(DeviceSigningService.class);
        when(signing.isEnabled()).thenReturn(true);
        when(signing.signingPublicKey()).thenReturn("public-key-base64");
        when(signing.signingKeyFingerprint()).thenReturn("public-key-fingerprint");

        var info = new ControlServerInfoService(control, node, signing).publicInfo();

        assertTrue(info.deviceIdentityEnabled());
        assertTrue(info.features().contains("DEVICE_IDENTITY"));
        assertEquals("public-key-base64", info.controlSigningPublicKey());
        assertEquals("public-key-fingerprint", info.controlSigningKeyFingerprint());
        assertFalse(info.toString().contains("private"));
    }
}
