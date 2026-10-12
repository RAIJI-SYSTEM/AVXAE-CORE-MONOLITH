package com.raiji.rides.domain;

import java.time.Instant;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.common.enums.RidePassengerStatus;
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
@Table(name = "ride_passengers", indexes = {
        @Index(name = "idx_ride_pass_ride", columnList = "ride_id"),
        @Index(name = "idx_ride_pass_passenger", columnList = "passenger_id")
})
public class RidePassenger extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    @Column(name = "passenger_id", nullable = false)
    private UUID passengerId;

    @Column(name = "passenger_name", length = 150)
    private String passengerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RidePassengerStatus status = RidePassengerStatus.PENDING;

    @Column(name = "joined_at")
    private Instant joinedAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    public void confirm() {
        this.status = RidePassengerStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
    }

    public void cancel(String reason) {
        this.status = RidePassengerStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public UUID getId() { return id; }
    public UUID getRideId() { return rideId; }
    public void setRideId(UUID v) { this.rideId = v; }
    public UUID getPassengerId() { return passengerId; }
    public void setPassengerId(UUID v) { this.passengerId = v; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String v) { this.passengerName = v; }
    public RidePassengerStatus getStatus() { return status; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public String getCancellationReason() { return cancellationReason; }
}
