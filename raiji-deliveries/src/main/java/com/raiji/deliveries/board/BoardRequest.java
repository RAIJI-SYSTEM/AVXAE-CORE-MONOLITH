package com.raiji.deliveries.board;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import com.raiji.common.base.AuditableEntity;
import com.raiji.deliveries.domain.PackageDetails;
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
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

/** Pedido agendado no mural da região (carona de passageiro ou encomenda). */
@Entity
@Table(name = "board_requests")
public class BoardRequest extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private BoardKind kind;

    @Column(name = "requester_sub", nullable = false, length = 64)
    private String requesterSub;

    @Column(name = "requester_name", length = 150)
    private String requesterName;

    @Column(name = "company_id")
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BoardStatus status = BoardStatus.OPEN;

    @Column(name = "origin_city", nullable = false, length = 100)
    private String originCity;

    @Column(name = "origin_state", nullable = false, length = 2)
    private String originState;

    @Column(name = "origin_location", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point originLocation;

    @Column(name = "origin_address", length = 255)
    private String originAddress;

    @Column(name = "destination_city", nullable = false, length = 100)
    private String destinationCity;

    @Column(name = "destination_state", nullable = false, length = 2)
    private String destinationState;

    @Column(name = "destination_location", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point destinationLocation;

    @Column(name = "destination_address", length = 255)
    private String destinationAddress;

    /** Hora de Brasília. */
    @Column(name = "desired_at", nullable = false)
    private LocalDateTime desiredAt;

    @Column(name = "notes", length = 500)
    private String notes;

    /** Só em PACKAGE (null em SEAT). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "size", column = @Column(name = "package_size")),
            @AttributeOverride(name = "description", column = @Column(name = "description")),
            @AttributeOverride(name = "weightKg", column = @Column(name = "weight_kg", precision = 8, scale = 2)),
            @AttributeOverride(name = "lengthCm", column = @Column(name = "length_cm")),
            @AttributeOverride(name = "widthCm", column = @Column(name = "width_cm")),
            @AttributeOverride(name = "heightCm", column = @Column(name = "height_cm")),
            @AttributeOverride(name = "fragile", column = @Column(name = "fragile")),
            @AttributeOverride(name = "declaredValue", column = @Column(name = "declared_value", precision = 12, scale = 2)),
            @AttributeOverride(name = "recipientName", column = @Column(name = "recipient_name")),
            @AttributeOverride(name = "recipientPhone", column = @Column(name = "recipient_phone"))
    })
    private PackageDetails pkg;

    @Column(name = "accepted_offer_id")
    private UUID acceptedOfferId;

    @Column(name = "ride_id")
    private UUID rideId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "delivery_id")
    private UUID deliveryId;

    @Column(name = "closed_at")
    private Instant closedAt;

    public BoardStatus statusAt(LocalDateTime now) {
        return status == BoardStatus.OPEN && !desiredAt.isAfter(now) ? BoardStatus.EXPIRED : status;
    }

    public UUID getId() { return id; }
    public BoardKind getKind() { return kind; }
    public void setKind(BoardKind v) { this.kind = v; }
    public String getRequesterSub() { return requesterSub; }
    public void setRequesterSub(String v) { this.requesterSub = v; }
    public String getRequesterName() { return requesterName; }
    public void setRequesterName(String v) { this.requesterName = v; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID v) { this.companyId = v; }
    public BoardStatus getStatus() { return status; }
    public void setStatus(BoardStatus v) { this.status = v; }
    public String getOriginCity() { return originCity; }
    public void setOriginCity(String v) { this.originCity = v; }
    public String getOriginState() { return originState; }
    public void setOriginState(String v) { this.originState = v; }
    public Point getOriginLocation() { return originLocation; }
    public void setOriginLocation(Point v) { this.originLocation = v; }
    public String getOriginAddress() { return originAddress; }
    public void setOriginAddress(String v) { this.originAddress = v; }
    public String getDestinationCity() { return destinationCity; }
    public void setDestinationCity(String v) { this.destinationCity = v; }
    public String getDestinationState() { return destinationState; }
    public void setDestinationState(String v) { this.destinationState = v; }
    public Point getDestinationLocation() { return destinationLocation; }
    public void setDestinationLocation(Point v) { this.destinationLocation = v; }
    public String getDestinationAddress() { return destinationAddress; }
    public void setDestinationAddress(String v) { this.destinationAddress = v; }
    public LocalDateTime getDesiredAt() { return desiredAt; }
    public void setDesiredAt(LocalDateTime v) { this.desiredAt = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public PackageDetails getPkg() { return pkg; }
    public void setPkg(PackageDetails v) { this.pkg = v; }
    public UUID getAcceptedOfferId() { return acceptedOfferId; }
    public void setAcceptedOfferId(UUID v) { this.acceptedOfferId = v; }
    public UUID getRideId() { return rideId; }
    public void setRideId(UUID v) { this.rideId = v; }
    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID v) { this.bookingId = v; }
    public UUID getDeliveryId() { return deliveryId; }
    public void setDeliveryId(UUID v) { this.deliveryId = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
}
