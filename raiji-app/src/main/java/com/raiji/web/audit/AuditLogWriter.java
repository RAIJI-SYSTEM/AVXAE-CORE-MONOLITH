package com.raiji.web.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raiji.rides.service.AuditLogService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Grava a auditoria no Postgres ({@code audit_log}) fora da thread da requisição.
 * A resposta ao usuário nunca depende do banco de auditoria.
 */
@Component
public class AuditLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditLogWriter.class);

    private final AuditLogService auditLogService;
    private final Executor executor;
    private final ObjectMapper json;

    public AuditLogWriter(AuditLogService auditLogService,
                          @Qualifier("auditExecutor") Executor executor,
                          ObjectMapper json) {
        this.auditLogService = auditLogService;
        this.executor = executor;
        this.json = json;
    }

    public void write(AuditEvent event) {
        if (!event.persisted()) return;
        executor.execute(() -> {
            try {
                auditLogService.record(
                        event.action(),
                        event.entityType(),
                        event.entityId(),
                        event.actorSub(),
                        details(event));
            } catch (RuntimeException ex) {
                log.warn("Falha ao gravar auditoria {} {}: {}",
                        event.action(), event.path(), ex.getMessage());
            }
        });
    }

    private String details(AuditEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", event.method());
        fields.put("path", event.path());
        fields.put("status", event.status());
        fields.put("durationMs", event.durationMs());
        fields.put("requestId", event.requestId());
        fields.put("ip", event.clientIp());
        fields.put("userAgent", event.userAgent());
        fields.put("username", event.actorUsername());
        fields.put("roles", event.roles());
        fields.put("decision", event.decision());
        try {
            return json.writeValueAsString(fields);
        } catch (Exception ex) {
            return "{\"path\":\"" + event.path() + "\"}";
        }
    }
}
