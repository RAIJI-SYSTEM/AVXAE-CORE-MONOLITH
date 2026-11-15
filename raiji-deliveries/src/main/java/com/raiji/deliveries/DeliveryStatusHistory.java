package com.raiji.deliveries;

import java.time.Instant;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import jakarta.persistence.*;

@Entity
@Table(name = "delivery_status_history")
public class DeliveryStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private DeliveryStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private DeliveryStatus toStatus;

    @Column(name = "changed_by", length = 64)
    private String changedBy;

    @Column(length = 300)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DeliveryStatusHistory() {
    }

    public DeliveryStatusHistory(
            UUID deliveryId,
            DeliveryStatus fromStatus,
            DeliveryStatus toStatus,
            String changedBy,
            String reason
    ) {
        this.deliveryId = deliveryId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public DeliveryStatus getFromStatus() {
        return fromStatus;
    }

    public DeliveryStatus getToStatus() {
        return toStatus;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}