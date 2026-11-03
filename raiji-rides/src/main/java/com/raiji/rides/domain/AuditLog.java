package com.raiji.rides.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * S2-05 / RN04 — registro de auditoria.
 *
 * <p>Entidade enxuta e independente (não estende {@code AuditableEntity}):
 * representa eventos imutáveis "quem/o-quê/quando". Usada para registrar os
 * estornos ({@code ESTORNO_SUCESSO} com o id do refund, {@code ESTORNO_FALHOU}
 * com o erro). O mapeamento espelha exatamente a migração V12
 * (necessário para o {@code ddl-auto: validate}).
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_created", columnList = "created_at")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "actor_sub", length = 64)
    private String actorSub;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String v) { this.entityType = v; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID v) { this.entityId = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getActorSub() { return actorSub; }
    public void setActorSub(String v) { this.actorSub = v; }
    public String getDetails() { return details; }
    public void setDetails(String v) { this.details = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
