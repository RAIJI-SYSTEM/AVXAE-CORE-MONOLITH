package com.raiji.rides.repository;

import java.util.List;
import java.util.UUID;

import com.raiji.rides.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByEntityTypeAndEntityId(String entityType, UUID entityId);

    long countByAction(String action);

    long countByEntityTypeAndEntityIdAndAction(
            String entityType, UUID entityId, String action);
}
