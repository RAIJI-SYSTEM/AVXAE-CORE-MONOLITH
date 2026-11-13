package com.raiji.rides.domain;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "rides", indexes = {
        @Index(name = "idx_rides_route_date", columnList = "origin_city, destination_city, departure_date"),
        @Index(name = "idx_rides_driver_sub_status", columnList = "driver_sub, status")
})
public class Ride extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "ride_type", nullable = false)
    private RideType type;

    /** Sub do motorista no Keycloak. */
    @Column(name = "driver_sub", nullable = false, length = 64)
    private String driverSub;

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

    /** Comissão da plataforma (%) vigente quando a carona foi publicada. */
    @Column(name = "platform_fee_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal platformFeePercent = BigDecimal.ZERO;

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

    @Column(name = "origin_location", columnDefinition = "geography(Point,4326)")
    private Point originLocation;

    @Column(name = "destination_location", columnDefinition = "geography(Point,4326)")
    private Point destinationLocation;

    @Column(name = "origin_address", length = 255)
    private String originAddress;

    @Column(name = "destination_address", length = 255)
    private String destinationAddress;

    /** Onde o motorista encontra os passageiros (texto livre: "portão 2 do shopping"). */
    /** Preço por encomenda (CARGO/MIXED); null = grátis. A comissão vale igual. */
    @Column(name = "price_per_package", precision = 10, scale = 2)
    private BigDecimal pricePerPackage;

    @Column(name = "meeting_point", length = 255)
    private String meetingPoint;

    @Column(name = "route", columnDefinition = "geography(LineString,4326)")
    private LineString route;

    @Column(name = "route_distance_meters")
    private Integer routeDistanceMeters;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private RideVisibility visibility = RideVisibility.PUBLIC;

    @Column(name = "bookings_closed", nullable = false)
    private boolean bookingsClosed;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    /** Leva passageiros? (PASSENGER ou MIXED) */
    public boolean carriesPassengers() {
        return type == RideType.PASSENGER || type == RideType.MIXED;
    }

    /** Leva encomendas? (CARGO ou MIXED) */
    public boolean carriesPackages() {
        return type == RideType.CARGO || type == RideType.MIXED;
    }

    /** Aceita pedidos novos agora? */
    public boolean openForBookings() {
        return status == RideStatus.PLANNED && !bookingsClosed && getDeletedAt() == null;
    }

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
    public String getDriverSub() { return driverSub; }
    public void setDriverSub(String v) { this.driverSub = v; }
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
    public BigDecimal getPlatformFeePercent() { return platformFeePercent; }
    public void setPlatformFeePercent(BigDecimal v) { this.platformFeePercent = v; }
    public Integer getTotalSeats() { return totalSeats; }
    public void setTotalSeats(Integer v) { this.totalSeats = v; }
    public Integer getAvailableSeats() { return availableSeats; }
    public void setAvailableSeats(Integer v) { this.availableSeats = v; }
    public RideStatus getStatus() { return status; }
    public void setStatus(RideStatus v) { this.status = v; }
    public Point getOriginLocation() { return originLocation; }
    public void setOriginLocation(Point v) { this.originLocation = v; }
    public Point getDestinationLocation() { return destinationLocation; }
    public void setDestinationLocation(Point v) { this.destinationLocation = v; }
    public String getOriginAddress() { return originAddress; }
    public void setOriginAddress(String v) { this.originAddress = v; }
    public String getDestinationAddress() { return destinationAddress; }
    public void setDestinationAddress(String v) { this.destinationAddress = v; }
    public BigDecimal getPricePerPackage() { return pricePerPackage; }
    public void setPricePerPackage(BigDecimal v) { this.pricePerPackage = v; }
    public String getMeetingPoint() { return meetingPoint; }
    public void setMeetingPoint(String v) { this.meetingPoint = v; }
    public LineString getRoute() { return route; }
    public void setRoute(LineString v) { this.route = v; }
    public Integer getRouteDistanceMeters() { return routeDistanceMeters; }
    public void setRouteDistanceMeters(Integer v) { this.routeDistanceMeters = v; }
    public RideVisibility getVisibility() { return visibility; }
    public void setVisibility(RideVisibility v) { this.visibility = v; }
    public boolean isBookingsClosed() { return bookingsClosed; }
    public void setBookingsClosed(boolean v) { this.bookingsClosed = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant v) { this.cancelledAt = v; }
    public String getCancellationReason() { return cancellationReason; }
    public void setCancellationReason(String v) { this.cancellationReason = v; }
    public CargoDetails getCargoDetails() { return cargoDetails; }
    public void setCargoDetails(CargoDetails v) { this.cargoDetails = v; }
}
