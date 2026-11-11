
package com.raiji.rides.domain;

import com.raiji.common.base.AuditableEntity;
import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentStatus;
import com.raiji.rides.domain.enums.RideIntentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ride_intents")
public class RideIntent extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_sub", nullable = false, length = 64)
    private String userSub;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RideIntentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RideIntentMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RideIntentStatus status = RideIntentStatus.ACTIVE;

    @Column(name = "origin_city", length = 100)
    private String originCity;

    @Column(name = "origin_state", length = 2)
    private String originState;

    @Column(name = "destination_city", length = 100)
    private String destinationCity;

    @Column(name = "destination_state", length = 2)
    private String destinationState;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "origin_location", columnDefinition = "geography(Point,4326)")
    private org.locationtech.jts.geom.Point originLocation;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "destination_location", columnDefinition = "geography(Point,4326)")
    private org.locationtech.jts.geom.Point destinationLocation;

    @Column(name = "desired_at")
    private OffsetDateTime desiredAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public RideIntent() {
    }

    public UUID getId() {
        return id;
    }

    public String getUserSub() {
        return userSub;
    }

    public void setUserSub(String userSub) {
        this.userSub = userSub;
    }

    public RideIntentType getType() {
        return type;
    }

    public void setType(RideIntentType type) {
        this.type = type;
    }

    public RideIntentMode getMode() {
        return mode;
    }

    public void setMode(RideIntentMode mode) {
        this.mode = mode;
    }

    public RideIntentStatus getStatus() {
        return status;
    }

    public void setStatus(RideIntentStatus status) {
        this.status = status;
    }

    public String getOriginCity() {
        return originCity;
    }

    public void setOriginCity(String originCity) {
        this.originCity = originCity;
    }

    public String getOriginState() {
        return originState;
    }

    public void setOriginState(String originState) {
        this.originState = originState;
    }

    public String getDestinationCity() {
        return destinationCity;
    }

    public void setDestinationCity(String destinationCity) {
        this.destinationCity = destinationCity;
    }

    public String getDestinationState() {
        return destinationState;
    }

    public void setDestinationState(String destinationState) {
        this.destinationState = destinationState;
    }

    public org.locationtech.jts.geom.Point getOriginLocation() {
        return originLocation;
    }

    public void setOriginLocation(org.locationtech.jts.geom.Point originLocation) {
        this.originLocation = originLocation;
    }

    public org.locationtech.jts.geom.Point getDestinationLocation() {
        return destinationLocation;
    }

    public void setDestinationLocation(
            org.locationtech.jts.geom.Point destinationLocation) {
        this.destinationLocation = destinationLocation;
    }

    public OffsetDateTime getDesiredAt() {
        return desiredAt;
    }

    public void setDesiredAt(OffsetDateTime desiredAt) {
        this.desiredAt = desiredAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
