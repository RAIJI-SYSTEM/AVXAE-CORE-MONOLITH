package com.raiji.rides.service;

import java.time.Instant;
import java.util.UUID;

import com.raiji.rides.domain.AuditLog;
import com.raiji.rides.repository.AuditLogRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S2-05 / RN04 — escrita na trilha de auditoria ({@code audit_log}).
 *
 * <p>Usa {@code REQUIRES_NEW} para que o registro de auditoria sobreviva mesmo
 * que a transação externa venha a falhar depois — a trilha é "append-only" e
 * não deve ser perdida. Em testes sem transação ativa, abre uma própria.
 */
@Service
public class AuditLogService {

    private static final Logger log =
            LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog record(String action,
                           String entityType,
                           UUID entityId,
                           String actorSub,
                           String details) {

        AuditLog entry = new AuditLog();
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setActorSub(actorSub);
        entry.setDetails(details);
        entry.setCreatedAt(Instant.now());

        AuditLog saved = auditLogRepository.save(entry);

        log.info("Audit [{}] {} {} por {}", action, entityType, entityId, actorSub);
        return saved;
    }
}
