package com.lanchat.service;

import com.lanchat.config.LanChatNodeProperties;
import com.lanchat.config.LanChatPrivateDeploymentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NodeDiagnosticsServiceTest {

    @Test
    void publicInfoExposesCapabilitiesWithoutDependencyAddressesOrSecrets() {
        LanChatNodeProperties node = new LanChatNodeProperties();
        node.setId("node-office-01");
        node.setName("Office Node");
        node.setOrganizationName("Example Org");
        node.setMode("lan-first");

        LanChatPrivateDeploymentProperties privateDeployment =
                new LanChatPrivateDeploymentProperties();
        privateDeployment.setEnabled(true);
        privateDeployment.setSelfRegistrationEnabled(true);
        privateDeployment.setBootstrapAdminPassword("must-not-leak");

        NodeDiagnosticsService service = new NodeDiagnosticsService(
                node,
                privateDeployment,
                mock(JdbcTemplate.class),
                mock(StringRedisTemplate.class)
        );
        ReflectionTestUtils.setField(service, "discoveryEnabled", true);

        var info = service.publicInfo();

        assertEquals("node-office-01", info.nodeId());
        assertEquals("LAN_FIRST", info.mode());
        assertTrue(info.discoveryEnabled());
        assertFalse(info.selfRegistrationEnabled());
        assertTrue(info.capabilities().contains("CONNECTION_DIAGNOSTICS"));
        assertTrue(info.capabilities().contains("MDNS_DISCOVERY"));
        assertTrue(info.capabilities().contains("MULTI_INSTANCE_ROUTING"));
        assertFalse(info.toString().contains("must-not-leak"));
    }
}
