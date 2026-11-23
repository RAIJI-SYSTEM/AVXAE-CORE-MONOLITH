package com.raiji.deliveries.board;

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

/** Oferta de um motorista: o valor e em que viagem leva (carona já publicada ou viagem nova). */
@Entity
@Table(name = "board_offers")
public class BoardOffer extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "driver_sub", nullable = false, length = 64)
    private String driverSub;

    @Column(name = "driver_name", length = 150)
    private String driverName;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "ride_id")
    private UUID rideId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BoardOfferStatus status = BoardOfferStatus.PENDING;

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID v) { this.requestId = v; }
    public String getDriverSub() { return driverSub; }
    public void setDriverSub(String v) { this.driverSub = v; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String v) { this.driverName = v; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal v) { this.price = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public UUID getRideId() { return rideId; }
    public void setRideId(UUID v) { this.rideId = v; }
    public UUID getVehicleId() { return vehicleId; }
    public void setVehicleId(UUID v) { this.vehicleId = v; }
    public BoardOfferStatus getStatus() { return status; }
    public void setStatus(BoardOfferStatus v) { this.status = v; }
}
