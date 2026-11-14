package com.raiji.rides.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.common.enums.RidePassengerStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "ride_passengers", indexes = {
        @Index(name = "idx_ride_pass_ride", columnList = "ride_id"),
        @Index(name = "idx_ride_pass_passenger_sub", columnList = "passenger_sub")
})
public class RidePassenger extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    /** Sub do passageiro no Keycloak. */
    @Column(name = "passenger_sub", nullable = false, length = 64)
    private String passengerSub;

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

    /** S2-04 / RF09 — momento em que a reserva foi CANCELLED (a "data"). */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * S2-05 / RN04 — PaymentIntent do Stripe associado à reserva paga.
     * Preenchido no aceite (ACCEPT) e usado para o estorno no cancelamento.
     */
    @Column(name = "payment_intent_id", length = 100)
    private String paymentIntentId;

    /**
     * S2-01 / RF18 — bagagem declarada na solicitação.
     * Cascade ALL: persistida junto com a solicitação na MESMA transação.
     */
    @OneToMany(
            mappedBy = "ridePassenger",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<Baggage> baggage = new ArrayList<>();

    /** Onde embarca (null = na origem da viagem). */
    @Column(name = "pickup_location", columnDefinition = "geography(Point,4326)")
    private Point pickupLocation;

    /** Onde desembarca (null = no destino da viagem). */
    @Column(name = "dropoff_location", columnDefinition = "geography(Point,4326)")
    private Point dropoffLocation;

    @Column(name = "pickup_address", length = 255)
    private String pickupAddress;

    @Column(name = "dropoff_address", length = 255)
    private String dropoffAddress;

    /** Viagem inteira (origem -> destino)? Só estes entram no "aceitar todos". */
    @Column(name = "full_route", nullable = false)
    private boolean fullRoute = true;

    /** Preço desta vaga (o da viagem ou o proporcional do trecho); null = gratuita. */
    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "completed_at")
    private Instant completedAt;

    public void complete() {
        this.status = RidePassengerStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** Bagagem em vigor (sem a alteração que espera aprovação do motorista). */
    public List<Baggage> activeBaggage() {
        return baggage.stream().filter(b -> !b.isPendingApproval()).toList();
    }

    /** Alteração de bagagem esperando o motorista aprovar (vazia se não houver). */
    public List<Baggage> pendingBaggage() {
        return baggage.stream().filter(Baggage::isPendingApproval).toList();
    }

    public void confirm() {
        this.status = RidePassengerStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
    }

    /**
     * S2-02 (REJECT) + S2-04 (RF09): marca a reserva como CANCELLED, guarda o
     * motivo e registra a data/hora do cancelamento. Método único reutilizado
     * pelos dois fluxos (observação da issue S2-04: "reutilizar
     * RidePassenger.cancel(reason) já existente").
     */
    public void cancel(String reason) {
        this.status = RidePassengerStatus.CANCELLED;
        this.cancellationReason = reason;
        this.cancelledAt = Instant.now();
    }

    /** Adiciona um item de bagagem mantendo os dois lados do relacionamento. */
    public void addBaggage(Baggage item) {
        item.setRidePassenger(this);
        this.baggage.add(item);
    }

    public UUID getId() { return id; }
    public UUID getRideId() { return rideId; }
    public void setRideId(UUID v) { this.rideId = v; }
    public String getPassengerSub() { return passengerSub; }
    public void setPassengerSub(String v) { this.passengerSub = v; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String v) { this.passengerName = v; }
    public RidePassengerStatus getStatus() { return status; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
    public String getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(String v) { this.paymentIntentId = v; }
    public List<Baggage> getBaggage() { return baggage; }
    public Point getPickupLocation() { return pickupLocation; }
    public void setPickupLocation(Point v) { this.pickupLocation = v; }
    public Point getDropoffLocation() { return dropoffLocation; }
    public void setDropoffLocation(Point v) { this.dropoffLocation = v; }
    public String getPickupAddress() { return pickupAddress; }
    public void setPickupAddress(String v) { this.pickupAddress = v; }
    public String getDropoffAddress() { return dropoffAddress; }
    public void setDropoffAddress(String v) { this.dropoffAddress = v; }
    public boolean isFullRoute() { return fullRoute; }
    public void setFullRoute(boolean v) { this.fullRoute = v; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal v) { this.price = v; }
    public Instant getCompletedAt() { return completedAt; }
}
