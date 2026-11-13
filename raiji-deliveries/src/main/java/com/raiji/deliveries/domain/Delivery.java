package com.raiji.deliveries.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.common.enums.DeliveryStatus;
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

/** Encomenda que viaja numa carona (pedido de "espaço de carga"). */
@Entity
@Table(name = "deliveries")
public class Delivery extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    @Column(name = "sender_sub", nullable = false, length = 64)
    private String senderSub;

    @Column(name = "sender_name", length = 150)
    private String senderName;

    @Column(name = "company_id")
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Embedded
    private PackageDetails pkg = new PackageDetails();

    @Column(name = "pickup_location", columnDefinition = "geography(Point,4326)")
    private Point pickupLocation;

    @Column(name = "pickup_address", length = 255)
    private String pickupAddress;

    @Column(name = "dropoff_location", columnDefinition = "geography(Point,4326)")
    private Point dropoffLocation;

    @Column(name = "dropoff_address", length = 255)
    private String dropoffAddress;

    @Column(name = "full_route", nullable = false)
    private boolean fullRoute = true;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "platform_fee_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal platformFeePercent = BigDecimal.ZERO;

    @Column(name = "pickup_code", nullable = false, length = 4)
    private String pickupCode;

    @Column(name = "delivery_keyword", nullable = false, length = 12)
    private String deliveryKeyword;

    @Column(name = "failed_code_attempts", nullable = false)
    private int failedCodeAttempts;

    @Column(name = "freight_request_id")
    private UUID freightRequestId;

    @Column(name = "payment_intent_id", length = 100)
    private String paymentIntentId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    public void confirm() {
        status = DeliveryStatus.CONFIRMED;
        confirmedAt = Instant.now();
    }

    public void pickUp() {
        status = DeliveryStatus.IN_TRANSIT;
        pickedUpAt = Instant.now();
        failedCodeAttempts = 0;
    }

    public void deliver() {
        status = DeliveryStatus.DELIVERED;
        deliveredAt = Instant.now();
    }

    public void cancel(String reason) {
        status = DeliveryStatus.CANCELLED;
        cancelledAt = Instant.now();
        cancellationReason = reason;
    }

    public boolean active() {
        return status == DeliveryStatus.PENDING || status == DeliveryStatus.CONFIRMED
                || status == DeliveryStatus.IN_TRANSIT;
    }

    public UUID getId() { return id; }
    public UUID getRideId() { return rideId; }
    public void setRideId(UUID v) { this.rideId = v; }
    public String getSenderSub() { return senderSub; }
    public void setSenderSub(String v) { this.senderSub = v; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String v) { this.senderName = v; }
    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID v) { this.companyId = v; }
    public DeliveryStatus getStatus() { return status; }
    public PackageDetails getPkg() { return pkg; }
    public void setPkg(PackageDetails v) { this.pkg = v; }
    public Point getPickupLocation() { return pickupLocation; }
    public void setPickupLocation(Point v) { this.pickupLocation = v; }
    public String getPickupAddress() { return pickupAddress; }
    public void setPickupAddress(String v) { this.pickupAddress = v; }
    public Point getDropoffLocation() { return dropoffLocation; }
    public void setDropoffLocation(Point v) { this.dropoffLocation = v; }
    public String getDropoffAddress() { return dropoffAddress; }
    public void setDropoffAddress(String v) { this.dropoffAddress = v; }
    public boolean isFullRoute() { return fullRoute; }
    public void setFullRoute(boolean v) { this.fullRoute = v; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal v) { this.price = v; }
    public BigDecimal getPlatformFeePercent() { return platformFeePercent; }
    public void setPlatformFeePercent(BigDecimal v) { this.platformFeePercent = v; }
    public String getPickupCode() { return pickupCode; }
    public void setPickupCode(String v) { this.pickupCode = v; }
    public String getDeliveryKeyword() { return deliveryKeyword; }
    public void setDeliveryKeyword(String v) { this.deliveryKeyword = v; }
    public int getFailedCodeAttempts() { return failedCodeAttempts; }
    public void setFailedCodeAttempts(int v) { this.failedCodeAttempts = v; }
    public UUID getFreightRequestId() { return freightRequestId; }
    public void setFreightRequestId(UUID v) { this.freightRequestId = v; }
    public String getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(String v) { this.paymentIntentId = v; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getPickedUpAt() { return pickedUpAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
}
