package com.raiji.rides.domain;

import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "recipient_sub", nullable = false, length = 64)
    private String recipientSub;

    /** Carona relacionada (null em avisos de empresa, frete etc.). */
    @Column(name = "ride_id")
    private UUID rideId;

    /** Assunto, para o app abrir a tela certa (ex.: RIDE_REQUEST, DELIVERY_ACCEPTED, COMPANY_INVITE). */
    @Column(name = "kind", length = 40)
    private String kind;

    /** Id do que a notificação fala (pedido, encomenda, empresa, frete...). */
    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "read_at")
    private java.time.Instant readAt;

    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public UUID getReferenceId() { return referenceId; }
    public void setReferenceId(UUID v) { this.referenceId = v; }
    public java.time.Instant getReadAt() { return readAt; }
    public void setReadAt(java.time.Instant v) { this.readAt = v; }

    @Column(nullable = false, length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationAudience audience;

    public UUID getId() {
        return id;
    }

    public NotificationAudience getAudience() {
        return audience;
    }

    public void setAudience(NotificationAudience audience) {
        this.audience = audience;
    }

    public String getRecipientSub() {
        return recipientSub;
    }

    public void setRecipientSub(String recipientSub) {
        this.recipientSub = recipientSub;
    }

    public UUID getRideId() {
        return rideId;
    }

    public void setRideId(UUID rideId) {
        this.rideId = rideId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}