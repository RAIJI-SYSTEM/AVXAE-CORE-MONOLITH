package com.raiji.web.audit;

import java.util.List;
import java.util.UUID;

/**
 * Evento de auditoria de uma requisição HTTP.
 *
 * <p>Não carrega corpo, token nem conteúdo de mensagem: apenas quem fez, o quê,
 * quando, em qual rota, com qual resultado e quanto tempo levou.
 */
public record AuditEvent(
        String service,
        String event,
        String requestId,
        String method,
        String path,
        String query,
        int status,
        long durationMs,
        String actorSub,
        String actorUsername,
        List<String> roles,
        String clientIp,
        String userAgent,
        String action,
        String entityType,
        UUID entityId,
        String decision,
        String outcome,
        boolean persisted) {
}
