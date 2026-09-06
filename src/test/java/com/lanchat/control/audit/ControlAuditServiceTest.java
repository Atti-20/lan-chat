package com.lanchat.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanchat.control.config.ControlServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlAuditServiceTest {

    private ControlAuditMapper mapper;
    private ControlAuditService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ControlAuditMapper.class);
        ControlServerProperties properties = new ControlServerProperties();
        properties.setOrganizationId("org-acme");
        service = new ControlAuditService(mapper, properties, new ObjectMapper());
    }

    @Test
    void appendWritesBoundedMachineDetailWithoutRequiringAUsername() {
        when(mapper.insertEvent(
                eq("org-acme"), eq(7L), eq(null), eq("ROLE_GRANTED"),
                eq("ORGANIZATION_MEMBER"), eq("71"), eq("SUCCEEDED"),
                eq(null), eq("{\"roleCode\":\"AUDITOR\"}")))
                .thenReturn(1);

        service.appendRequired(
                7L,
                "role_granted",
                "organization_member",
                "71",
                "succeeded",
                Map.of("roleCode", "AUDITOR"));

        verify(mapper).insertEvent(
                "org-acme", 7L, null, "ROLE_GRANTED", "ORGANIZATION_MEMBER", "71",
                "SUCCEEDED", null, "{\"roleCode\":\"AUDITOR\"}");
    }

    @Test
    void queryClampsLimitAndNormalizesFilters() {
        when(mapper.selectRecent("org-acme", "ROLE_GRANTED", "DENIED", 200))
                .thenReturn(List.of());

        assertEquals(List.of(), service.recent("role_granted", "denied", 9999));

        verify(mapper).selectRecent("org-acme", "ROLE_GRANTED", "DENIED", 200);
    }

    @Test
    void unknownOutcomeIsRejectedBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recent(null, "MAYBE", 10));
    }
}
