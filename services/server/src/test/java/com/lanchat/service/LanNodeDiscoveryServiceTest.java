package com.lanchat.service;

import com.lanchat.config.LanChatNodeProperties;
import com.lanchat.config.LanChatProtocol;
import com.lanchat.control.config.ControlServerProperties;
import com.lanchat.control.protocol.ControlProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.jmdns.ServiceInfo;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanNodeDiscoveryServiceTest {

    private LanNodeDiscoveryService service;

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
    }

    @Test
    void resolvesDiscoveredPeerToSafeLanApplicationUrl() throws Exception {
        LanChatNodeProperties current = new LanChatNodeProperties();
        current.setId("current-node");
        service = new LanNodeDiscoveryService(current, new ControlServerProperties());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nodeId", "peer-node-01");
        properties.put("nodeName", "Meeting Room");
        properties.put("organization", "Example Org");
        properties.put("version", "2.1.0");
        properties.put("mode", "LAN_FIRST");
        properties.put("secure", "false");
        properties.put("protocol", "1");
        ServiceInfo peer = ServiceInfo.create(
                LanNodeDiscoveryService.LEGACY_SERVICE_TYPE,
                "Meeting Room-peer01",
                8080,
                0,
                0,
                properties
        );

        ReflectionTestUtils.invokeMethod(
                service, "remember", peer, InetAddress.getByName("192.168.10.24"), false);

        var nodes = service.listDiscoveredNodes();
        assertEquals(1, nodes.size());
        assertEquals("peer-node-01", nodes.get(0).nodeId());
        assertEquals("http://192.168.10.24:8080/app/", nodes.get(0).appUrl());
        assertFalse(nodes.get(0).current());
    }

    @Test
    void txtContractUsesThePublicNodeProtocolConstants() {
        LanChatNodeProperties current = new LanChatNodeProperties();
        current.setId("current-node");
        current.setName("Office Node");
        current.setOrganizationName("Example Org");
        current.setAdvertisedHost("chat.example.com");
        ControlServerProperties control = new ControlServerProperties();
        control.setId("control-office-01");
        control.setOrganizationId("org-example");
        service = new LanNodeDiscoveryService(current, control);

        Map<String, Object> txt = service.buildTxtProperties("current-node");

        assertEquals(Integer.toString(LanChatProtocol.PROTOCOL_VERSION),
                txt.get("protocolVersion"));
        assertEquals(LanChatProtocol.API_BASE_PATH, txt.get("apiBasePath"));
        assertEquals(LanChatProtocol.WEB_SOCKET_PATH, txt.get("webSocketPath"));
        assertEquals(LanChatProtocol.HEALTH_PATH, txt.get("healthPath"));
        assertEquals(LanChatProtocol.APP_PATH, txt.get("appPath"));
        assertEquals(Boolean.toString(LanChatProtocol.DESKTOP_AUTH_SUPPORTED),
                txt.get("desktopAuthSupported"));
        assertEquals("chat.example.com", txt.get("advertisedHost"));
        assertEquals(LanChatProtocol.REFRESH_TRANSPORT, txt.get("refreshTransport"));
        assertEquals(txt.get("protocolVersion"), txt.get("protocol"));
        assertEquals(txt.get("appPath"), txt.get("path"));

        Map<String, Object> controlTxt = service.buildControlTxtProperties(
                "control-office-01");
        assertEquals("control-office-01", controlTxt.get("controlId"));
        assertEquals("org-example", controlTxt.get("organizationId"));
        assertEquals(Integer.toString(ControlProtocol.PROTOCOL_VERSION),
                controlTxt.get("protocolVersion"));
        assertEquals(ControlProtocol.INFO_PATH, controlTxt.get("infoPath"));
        assertEquals(ControlProtocol.HEALTH_PATH, controlTxt.get("healthPath"));
        assertEquals(LanChatProtocol.API_BASE_PATH, controlTxt.get("apiBasePath"));
    }

    @Test
    void resolvesV2ControlAdvertisementWithSeparatedIdentity() throws Exception {
        LanChatNodeProperties current = new LanChatNodeProperties();
        ControlServerProperties control = new ControlServerProperties();
        control.setId("control-current-01");
        service = new LanNodeDiscoveryService(current, control);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("controlId", "control-peer-01");
        properties.put("controlName", "Peer Control");
        properties.put("organizationId", "org-example");
        properties.put("organizationName", "Example Org");
        properties.put("protocolVersion", "2");
        properties.put("version", "0.3.0");
        properties.put("mode", "LAN_FIRST");
        properties.put("secure", "false");
        ServiceInfo peer = ServiceInfo.create(
                LanNodeDiscoveryService.CONTROL_SERVICE_TYPE,
                "Peer Control",
                8080,
                0,
                0,
                properties
        );

        ReflectionTestUtils.invokeMethod(
                service, "rememberControl", peer,
                InetAddress.getByName("192.168.10.26"), false);

        var controls = service.listDiscoveredNodes();
        assertEquals(1, controls.size());
        assertEquals("control-peer-01", controls.get(0).nodeId());
        assertEquals("Example Org", controls.get(0).organizationName());
        assertEquals("http://192.168.10.26:8080/app/", controls.get(0).appUrl());
    }

    @Test
    void incompatibleProtocolIsNotAddedToDiscoveries() throws Exception {
        LanChatNodeProperties current = new LanChatNodeProperties();
        current.setId("current-node");
        service = new LanNodeDiscoveryService(current, new ControlServerProperties());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nodeId", "future-node-01");
        properties.put("nodeName", "Future Node");
        properties.put("protocolVersion", "2");
        ServiceInfo peer = ServiceInfo.create(
                LanNodeDiscoveryService.LEGACY_SERVICE_TYPE,
                "Future Node",
                8080,
                0,
                0,
                properties
        );

        ReflectionTestUtils.invokeMethod(
                service, "remember", peer, InetAddress.getByName("192.168.10.25"), false);

        assertTrue(service.listDiscoveredNodes().isEmpty());
    }
}
