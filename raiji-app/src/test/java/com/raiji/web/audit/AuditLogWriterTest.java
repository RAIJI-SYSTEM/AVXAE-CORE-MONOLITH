package com.raiji.web.audit;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raiji.rides.service.AuditLogService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuditLogWriterTest {

    @Mock
    private AuditLogService service;

    private final Executor direct = Runnable::run;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void persistsStateChangeWithDetails() throws Exception {
        UUID rideId = UUID.randomUUID();
        AuditEvent event = new AuditEvent(
                "avxae-core-monolith", "http_request", "req-1", "POST",
                "/api/rides", null, 201, 15, "sub-1", "maria",
                List.of("ROLE_DRIVER"), "10.0.0.1", "test",
                "RIDE_CREATED", "RIDE", rideId, null, "SUCCESS", true);

        new AuditLogWriter(service, direct, json).write(event);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(service).record(eq("RIDE_CREATED"), eq("RIDE"), eq(rideId), eq("sub-1"), details.capture());

        JsonNode node = json.readTree(details.getValue());
        assertThat(node.get("path").asText()).isEqualTo("/api/rides");
        assertThat(node.get("status").asInt()).isEqualTo(201);
        assertThat(node.get("requestId").asText()).isEqualTo("req-1");
        assertThat(node.get("username").asText()).isEqualTo("maria");
    }

    @Test
    void ignoresEventsNotMarkedForPersistence() {
        new AuditLogWriter(service, direct, json).write(new AuditEvent(
                "avxae-core-monolith", "http_request", "req-1", "GET",
                "/api/rides", null, 200, 2, "sub-1", "maria",
                List.of(), null, null, "HTTP_GET", "HTTP", null, null, "SUCCESS", false));
        verifyNoInteractions(service);
    }

    @Test
    void databaseFailureDoesNotPropagate() {
        AuditEvent event = new AuditEvent(
                "avxae-core-monolith", "http_request", "req-1", "POST",
                "/api/rides", null, 201, 15, "sub-1", "maria",
                List.of(), null, null, "RIDE_CREATED", "RIDE", UUID.randomUUID(),
                null, "SUCCESS", true);
        doThrow(new RuntimeException("db down"))
                .when(service).record(anyString(), anyString(), any(), anyString(), anyString());

        new AuditLogWriter(service, direct, json).write(event);
        verify(service).record(anyString(), anyString(), any(), anyString(), anyString());
    }
}
