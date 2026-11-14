package com.raiji.deliveries.freight;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.deliveries.domain.PackageDetails;
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

/**
 * Proposta de frete de uma empresa:
 * <ul>
 *   <li><b>direta</b> ({@code targetDriverSub} preenchido): para um motorista, com valor; ele aceita ou recusa;</li>
 *   <li><b>pública</b>: "preciso levar X para Y"; motoristas oferecem um valor e a empresa escolhe.</li>
 * </ul>
 * Fechado o frete, vira uma viagem EXCLUSIVA do motorista com a encomenda já aceita.
 */
@Entity
@Table(name = "freight_requests")
public class FreightRequest extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "created_by_sub", nullable = false, length = 64)
    private String createdBySub;

    @Column(name = "target_driver_sub", length = 64)
    private String targetDriverSub;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FreightStatus status = FreightStatus.OPEN;

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

    /** Quando a empresa quer que o motorista saia (hora de Brasília). */
    @Column(name = "desired_date", nullable = false)
    private LocalDateTime desiredDate;

    @Embedded
    private PackageDetails pkg = new PackageDetails();

    @Column(name = "offered_price", precision = 10, scale = 2)
    private BigDecimal offeredPrice;

    @Column(length = 500)
    private String notes;

    @Column(name = "accepted_offer_id")
    private UUID acceptedOfferId;
    @Column(name = "ride_id")
    private UUID rideId;
    @Column(name = "delivery_id")
    private UUID deliveryId;
    @Column(name = "decline_reason", length = 500)
    private String declineReason;
    @Column(name = "closed_at")
    private Instant closedAt;

    public boolean direct() {
        return targetDriverSub != null;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID v) { this.companyId = v; }
    public String getCreatedBySub() { return createdBySub; }
    public void setCreatedBySub(String v) { this.createdBySub = v; }
    public String getTargetDriverSub() { return targetDriverSub; }
    public void setTargetDriverSub(String v) { this.targetDriverSub = v; }
    public FreightStatus getStatus() { return status; }
    public void setStatus(FreightStatus v) { this.status = v; }
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
    public LocalDateTime getDesiredDate() { return desiredDate; }
    public void setDesiredDate(LocalDateTime v) { this.desiredDate = v; }
    public PackageDetails getPkg() { return pkg; }
    public void setPkg(PackageDetails v) { this.pkg = v; }
    public BigDecimal getOfferedPrice() { return offeredPrice; }
    public void setOfferedPrice(BigDecimal v) { this.offeredPrice = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public UUID getAcceptedOfferId() { return acceptedOfferId; }
    public void setAcceptedOfferId(UUID v) { this.acceptedOfferId = v; }
    public UUID getRideId() { return rideId; }
    public void setRideId(UUID v) { this.rideId = v; }
    public UUID getDeliveryId() { return deliveryId; }
    public void setDeliveryId(UUID v) { this.deliveryId = v; }
    public String getDeclineReason() { return declineReason; }
    public void setDeclineReason(String v) { this.declineReason = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
}
