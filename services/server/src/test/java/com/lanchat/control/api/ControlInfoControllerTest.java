package com.lanchat.control.api;

import com.lanchat.control.service.ControlServerInfoService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlInfoControllerTest {

    @Test
    void exposesInfoAndHealthThroughTheStandardEnvelope() {
        ControlServerInfoService service = mock(ControlServerInfoService.class);
        ControlInfo info = new ControlInfo(
                "control-office-01", "Office Control", "org-example", "Example",
                "LAN_FIRST", "AVAILABLE", 2,
                List.of("PASSWORD"), true, List.of("CHAT"),
                "0.3.0", "/api/v2", "/ws/chat", "/api/v2/control/health",
                "/app/", true, "HTTP_ONLY_COOKIE", "/api/v1",
                false, null, null, 1L);
        ControlHealth health = new ControlHealth(
                "UP", "control-office-01", 2, "0.3.0", 10L, 2L);
        when(service.publicInfo()).thenReturn(info);
        when(service.publicHealth()).thenReturn(health);
        ControlInfoController controller = new ControlInfoController(service);

        assertEquals(info, controller.info().getData());
        assertEquals(health, controller.health().getData());
    }
}
