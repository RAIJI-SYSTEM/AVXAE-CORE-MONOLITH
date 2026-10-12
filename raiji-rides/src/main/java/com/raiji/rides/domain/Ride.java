package com.raiji.rides.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Carona (ride).
 *
 * <p>Não possui associação JPA com {@link RidePassenger}: o design do monolito
 * modular usa referências por UUID (rideId / passengerId), o que facilita a
 * extração futura para microserviços sem refatoração de mapeamento.
 * Use {@code RidePassengerRepository.findByRideId(id)} para listar passageiros.
 */
@Entity
@Table(name = "rides", indexes = {
        @Index(name = "idx_rides_route_date", columnList = "origin_city, destination_city, departure_date"),
        @Index(name = "idx_rides_driver_status", columnList = "driver_id, status")
})
public class Ride extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "ride_type", nullable = false)
    private RideType type;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "driver_name", length = 150)
    private String driverName;

    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    @Column(name = "origin_city", nullable = false, length = 100)
    private String originCity;

    @Column(name = "origin_state", nullable = false, length = 2)
    private String originState;

    @Column(name = "destination_city", nullable = false, length = 100)
    private String destinationCity;

    @Column(name = "destination_state", nullable = false, length = 2)
    private String destinationState;

    @Column(name = "departure_date", nullable = false)
    private LocalDateTime departureDate;

    @Column(name = "price_per_passenger", precision = 10, scale = 2)
    private BigDecimal pricePerPassenger;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats = 4;

    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RideStatus status = RideStatus.PLANNED;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "maxWeightKg", column = @Column(name = "cargo_max_weight_kg")),
            @AttributeOverride(name = "maxLengthCm", column = @Column(name = "cargo_max_length_cm")),
            @AttributeOverride(name = "maxWidthCm",  column = @Column(name = "cargo_max_width_cm")),
            @AttributeOverride(name = "maxHeightCm", column = @Column(name = "cargo_max_height_cm")),
            @AttributeOverride(name = "observation", column = @Column(name = "cargo_observation"))
    })
    private CargoDetails cargoDetails;

    @PrePersist
    @PreUpdate
    private void validateCargo() {
        if ((this.type == RideType.CARGO || this.type == RideType.MIXED)
                && (this.cargoDetails == null || !this.cargoDetails.hasCargoCapacity())) {
            throw new IllegalStateException(
                    "Caronas de carga devem ter dimensões de espaço definidas");
        }
    }

    public UUID getId() { return id; }
    public RideType getType() { return type; }
    public void setType(RideType v) { this.type = v; }
    public UUID getDriverId() { return driverId; }
    public void setDriverId(UUID v) { this.driverId = v; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String v) { this.driverName = v; }
    public UUID getVehicleId() { return vehicleId; }
    public void setVehicleId(UUID v) { this.vehicleId = v; }
    public String getOriginCity() { return originCity; }
    public void setOriginCity(String v) { this.originCity = v; }
    public String getOriginState() { return originState; }
    public void setOriginState(String v) { this.originState = v; }
    public String getDestinationCity() { return destinationCity; }
    public void setDestinationCity(String v) { this.destinationCity = v; }
    public String getDestinationState() { return destinationState; }
    public void setDestinationState(String v) { this.destinationState = v; }
    public LocalDateTime getDepartureDate() { return departureDate; }
    public void setDepartureDate(LocalDateTime v) { this.departureDate = v; }
    public BigDecimal getPricePerPassenger() { return pricePerPassenger; }
    public void setPricePerPassenger(BigDecimal v) { this.pricePerPassenger = v; }
    public Integer getTotalSeats() { return totalSeats; }
    public void setTotalSeats(Integer v) { this.totalSeats = v; }
    public Integer getAvailableSeats() { return availableSeats; }
    public void setAvailableSeats(Integer v) { this.availableSeats = v; }
    public RideStatus getStatus() { return status; }
    public void setStatus(RideStatus v) { this.status = v; }
    public CargoDetails getCargoDetails() { return cargoDetails; }
    public void setCargoDetails(CargoDetails v) { this.cargoDetails = v; }
}
