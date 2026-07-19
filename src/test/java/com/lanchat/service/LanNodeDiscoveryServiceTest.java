package com.lanchat.service;

import com.lanchat.config.LanChatNodeProperties;
import com.lanchat.config.LanChatProtocol;
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
        service = new LanNodeDiscoveryService(current);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nodeId", "peer-node-01");
        properties.put("nodeName", "Meeting Room");
        properties.put("organization", "Example Org");
        properties.put("version", "2.1.0");
        properties.put("mode", "LAN_FIRST");
        properties.put("secure", "false");
        properties.put("protocol", "1");
        ServiceInfo peer = ServiceInfo.create(
                LanNodeDiscoveryService.SERVICE_TYPE,
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
        service = new LanNodeDiscoveryService(current);

        Map<String, Object> txt = service.buildTxtProperties("current-node");

        assertEquals(Integer.toString(LanChatProtocol.PROTOCOL_VERSION),
                txt.get("protocolVersion"));
        assertEquals(LanChatProtocol.API_BASE_PATH, txt.get("apiBasePath"));
        assertEquals(LanChatProtocol.WEB_SOCKET_PATH, txt.get("webSocketPath"));
        assertEquals(LanChatProtocol.HEALTH_PATH, txt.get("healthPath"));
        assertEquals(LanChatProtocol.APP_PATH, txt.get("appPath"));
        assertEquals(Boolean.toString(LanChatProtocol.DESKTOP_AUTH_SUPPORTED),
                txt.get("desktopAuthSupported"));
        assertEquals(LanChatProtocol.REFRESH_TRANSPORT, txt.get("refreshTransport"));
        assertEquals(txt.get("protocolVersion"), txt.get("protocol"));
        assertEquals(txt.get("appPath"), txt.get("path"));
    }

    @Test
    void incompatibleProtocolIsNotAddedToDiscoveries() throws Exception {
        LanChatNodeProperties current = new LanChatNodeProperties();
        current.setId("current-node");
        service = new LanNodeDiscoveryService(current);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nodeId", "future-node-01");
        properties.put("nodeName", "Future Node");
        properties.put("protocolVersion", "2");
        ServiceInfo peer = ServiceInfo.create(
                LanNodeDiscoveryService.SERVICE_TYPE,
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
