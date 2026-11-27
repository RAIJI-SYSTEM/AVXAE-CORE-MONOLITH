package com.raiji.web.audit;

import java.util.LinkedHashMap;
import java.util.Map;

import net.logstash.logback.marker.Markers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emite a trilha de auditoria como JSON puro no stdout (logger "AUDIT"),
 * configurado em {@code logback-spring.xml}.
 */
@Component
public class AuditEventLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    public void log(AuditEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", event.event());
        fields.put("request_id", event.requestId());
        fields.put("http_method", event.method());
        fields.put("http_path", event.path());
        if (event.query() != null && !event.query().isBlank()) fields.put("http_query", event.query());
        fields.put("http_status", event.status());
        fields.put("duration_ms", event.durationMs());
        if (event.actorSub() != null) fields.put("actor_sub", event.actorSub());
        if (event.actorUsername() != null) fields.put("actor_username", event.actorUsername());
        if (event.roles() != null && !event.roles().isEmpty()) fields.put("roles", event.roles());
        if (event.clientIp() != null) fields.put("client_ip", event.clientIp());
        if (event.userAgent() != null) fields.put("user_agent", event.userAgent());
        fields.put("action", event.action());
        fields.put("entity_type", event.entityType());
        if (event.entityId() != null) fields.put("entity_id", event.entityId().toString());
        if (event.decision() != null) fields.put("decision", event.decision());
        fields.put("outcome", event.outcome());
        fields.put("persisted", event.persisted());

        AUDIT.info(Markers.appendEntries(fields), "audit_http_request");
    }
}
