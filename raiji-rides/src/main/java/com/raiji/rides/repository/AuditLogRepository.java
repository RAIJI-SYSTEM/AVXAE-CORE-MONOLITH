package com.raiji.rides.repository;

import java.util.List;
import java.util.UUID;

import com.raiji.rides.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>,
        JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByEntityTypeAndEntityId(String entityType, UUID entityId);

    List<AuditLog> findTop200ByActorSubOrderByCreatedAtDesc(String actorSub);

    long countByAction(String action);

    long countByEntityTypeAndEntityIdAndAction(
            String entityType, UUID entityId, String action);
}
