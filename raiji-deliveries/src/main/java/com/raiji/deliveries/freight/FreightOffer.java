package com.raiji.deliveries.freight;

import java.math.BigDecimal;
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

/** Oferta de um motorista para um pedido de frete público. */
@Entity
@Table(name = "freight_offers")
public class FreightOffer extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "driver_sub", nullable = false, length = 64)
    private String driverSub;

    @Column(name = "driver_name", length = 150)
    private String driverName;

    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferStatus status = OfferStatus.PENDING;

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID v) { this.requestId = v; }
    public String getDriverSub() { return driverSub; }
    public void setDriverSub(String v) { this.driverSub = v; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String v) { this.driverName = v; }
    public UUID getVehicleId() { return vehicleId; }
    public void setVehicleId(UUID v) { this.vehicleId = v; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal v) { this.price = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public OfferStatus getStatus() { return status; }
    public void setStatus(OfferStatus v) { this.status = v; }
}
