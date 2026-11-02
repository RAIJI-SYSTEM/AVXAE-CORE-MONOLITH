package com.raiji.rides.domain;

import java.util.UUID;

import com.raiji.common.base.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * S2-01 / RF18 — Item de bagagem declarado na solicitação de vaga.
 *
 * <p>Tabela única com coluna {@code type} VARCHAR (sem tabela separada por
 * tipo). A soma de peso/volume para validação contra a capacidade da carona
 * será implementada em S2-03.
 */
@Entity
@Table(name = "baggage", indexes = {
        @Index(name = "idx_baggage_ride_passenger", columnList = "ride_passenger_id")
})
public class Baggage extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ride_passenger_id", nullable = false)
    private RidePassenger ridePassenger;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private BaggageType type;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "length_cm", nullable = false)
    private Integer lengthCm;

    @Column(name = "width_cm", nullable = false)
    private Integer widthCm;

    @Column(name = "height_cm", nullable = false)
    private Integer heightCm;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    public UUID getId() { return id; }

    public RidePassenger getRidePassenger() { return ridePassenger; }
    public void setRidePassenger(RidePassenger v) { this.ridePassenger = v; }

    public BaggageType getType() { return type; }
    public void setType(BaggageType v) { this.type = v; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer v) { this.quantity = v; }

    public Integer getLengthCm() { return lengthCm; }
    public void setLengthCm(Integer v) { this.lengthCm = v; }

    public Integer getWidthCm() { return widthCm; }
    public void setWidthCm(Integer v) { this.widthCm = v; }

    public Integer getHeightCm() { return heightCm; }
    public void setHeightCm(Integer v) { this.heightCm = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
}
