package com.raiji.deliveries.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.common.enums.DeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "deliveries", indexes = {
        @Index(name = "idx_deliveries_tracking", columnList = "tracking_code"),
        @Index(name = "idx_deliveries_status", columnList = "status")
})
public class Delivery extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tracking_code", unique = true, nullable = false, length = 20)
    private String trackingCode;

    @Column(name = "access_key", nullable = false, length = 6)
    private String accessKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "ride_id")
    private UUID rideId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "sender_name", length = 150)
    private String senderName;

    @Column(name = "recipient_name", nullable = false, length = 150)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(name = "recipient_document", length = 14)
    private String recipientDocument;

    @Column(name = "zip_code", nullable = false, length = 8)
    private String zipCode;
    @Column(name = "street", nullable = false, length = 150)
    private String street;
    @Column(name = "number", nullable = false, length = 20)
    private String number;
    @Column(name = "neighborhood", length = 100)
    private String neighborhood;
    @Column(name = "city", nullable = false, length = 100)
    private String city;
    @Column(name = "state", nullable = false, length = 2)
    private String state;
    @Column(name = "complement", length = 100)
    private String complement;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @PrePersist
    private void generateCodes() {
        if (this.trackingCode == null) {
            this.trackingCode = "RJJ" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + String.format("%04d", new Random().nextInt(10000));
        }
        if (this.accessKey == null) {
            this.accessKey = String.format("%06d", new Random().nextInt(1000000));
        }
    }

    public UUID getId() { return id; }
    public String getTrackingCode() { return trackingCode; }
    public void setTrackingCode(String v) { this.trackingCode = v; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String v) { this.accessKey = v; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus v) { this.status = v; }
    public UUID getRideId() { return rideId; }
    public void setRideId(UUID v) { this.rideId = v; }
    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID v) { this.senderId = v; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String v) { this.senderName = v; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String v) { this.recipientName = v; }
    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String v) { this.recipientPhone = v; }
    public String getRecipientDocument() { return recipientDocument; }
    public void setRecipientDocument(String v) { this.recipientDocument = v; }
    public String getZipCode() { return zipCode; }
    public void setZipCode(String v) { this.zipCode = v; }
    public String getStreet() { return street; }
    public void setStreet(String v) { this.street = v; }
    public String getNumber() { return number; }
    public void setNumber(String v) { this.number = v; }
    public String getNeighborhood() { return neighborhood; }
    public void setNeighborhood(String v) { this.neighborhood = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getComplement() { return complement; }
    public void setComplement(String v) { this.complement = v; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant v) { this.deliveredAt = v; }
}
