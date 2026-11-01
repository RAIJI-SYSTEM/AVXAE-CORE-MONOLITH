package com.raiji.rides.domain;

import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "recipient_sub", nullable = false, length = 64)
    private String recipientSub;

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    @Column(nullable = false, length = 500)
    private String message;

    public UUID getId() {
        return id;
    }

    public String getRecipientSub() {
        return recipientSub;
    }

    public void setRecipientSub(String recipientSub) {
        this.recipientSub = recipientSub;
    }

    public UUID getRideId() {
        return rideId;
    }

    public void setRideId(UUID rideId) {
        this.rideId = rideId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}