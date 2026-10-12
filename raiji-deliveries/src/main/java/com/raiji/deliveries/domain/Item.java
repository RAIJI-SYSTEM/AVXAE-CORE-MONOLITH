package com.raiji.deliveries.domain;

import java.math.BigDecimal;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.common.enums.ItemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "items", indexes = {
        @Index(name = "idx_items_delivery", columnList = "delivery_id")
})
public class Item extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "weight_kg", precision = 10, scale = 2, nullable = false)
    private BigDecimal weightKg;

    @Column(nullable = false)
    private boolean fragile = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemStatus status = ItemStatus.PENDING;

    public BigDecimal getTotalWeight() {
        return weightKg.multiply(BigDecimal.valueOf(quantity));
    }

    public UUID getId() { return id; }
    public UUID getDeliveryId() { return deliveryId; }
    public void setDeliveryId(UUID v) { this.deliveryId = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer v) { this.quantity = v; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal v) { this.weightKg = v; }
    public boolean isFragile() { return fragile; }
    public void setFragile(boolean v) { this.fragile = v; }
    public ItemStatus getStatus() { return status; }
    public void setStatus(ItemStatus v) { this.status = v; }
}
