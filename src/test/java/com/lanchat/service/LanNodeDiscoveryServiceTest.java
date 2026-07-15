package com.lanchat.service;

import com.lanchat.config.LanChatNodeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.jmdns.ServiceInfo;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
