package com.raiji.deliveries;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;
import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.PaymentStatus;
import com.raiji.rides.payment.Payable;
import com.raiji.deliveries.service.IllegalDeliveryStateException;
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
 * Encomenda que viaja numa carona (pedido de espaÃ§o de carga).
 */
@Entity
@Table(name = "deliveries")
public class Delivery extends AuditableEntity implements Payable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tracking_code", nullable = false, unique = true, length = 20)
    private String trackingCode;

    @Column(name = "ride_id")
    private UUID rideId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 20)
    private DeliveryMode mode;

    @Column(name = "origin_city",length = 100)
    private String originCity;

    @Column(name = "origin_state", length = 2)
    private String originState;

    @Column(name = "origin_address", length = 255)
    private String originAddress;

    @Column(name = "destination_city",length = 100)
    private String destinationCity;

    @Column(name = "destination_state", length = 2)
    private String destinationState;

    @Column(name = "destination_address", length = 255)
    private String destinationAddress;

    @Column(name = "distance_km", precision = 10, scale = 2)
    private BigDecimal distanceKm;

    @Column(name = "quoted_price_cents")
    private Long quotedPriceCents;

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

    @Column(name = "confirmation_code", nullable = false, length = 6)
    private String confirmationCode;

    @Column(name = "confirmation_attempts", nullable = false)
    private int confirmationAttempts;

    @Column(name = "reward_cents")
    private Long rewardCents;

    @Column(name = "confirmed_recipient_name", length = 120)
    private String confirmedRecipientName;

    @Column(name = "delivery_observation", length = 500)
    private String deliveryObservation;

    @Column(name = "failed_code_attempts", nullable = false)
    private int failedCodeAttempts;

    @Column(name = "freight_request_id")
    private UUID freightRequestId;

    @Column(name = "payment_intent_id", length = 100)
    private String paymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Column(name = "paid_amount_cents")
    private Long paidAmountCents;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "discount_cents")
    private Long discountCents;

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

    @Column(name = "driver_sub", length = 64)
    private String driverSub;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    // ---------------------------------------------------------
    // TransiÃ§Ãµes de estado
    // ---------------------------------------------------------

    /**
     * PENDING -> CONFIRMED
     */
    public void confirm() {
        requireStatus(
                DeliveryStatus.PENDING,
                "SÃ³ uma encomenda pendente pode ser aceita."
        );

        status = DeliveryStatus.CONFIRMED;
        confirmedAt = Instant.now();
    }

    /**
     * CONFIRMED -> IN_TRANSIT
     */
    public void pickUp() {
        requireStatus(
                DeliveryStatus.CONFIRMED,
                "SÃ³ uma encomenda aceita pode ser coletada."
        );

        status = DeliveryStatus.IN_TRANSIT;
        pickedUpAt = Instant.now();
        failedCodeAttempts = 0;
    }

    /**
     * IN_TRANSIT -> DELIVERED.
     * A conclusão exige que o serviço valide o código do destinatário.
     */
    public void deliver(Instant at, Long rewardCents) {
        requireStatus(
                DeliveryStatus.IN_TRANSIT,
                "Só uma encomenda em trânsito pode ser entregue."
        );

        status = DeliveryStatus.DELIVERED;
        deliveredAt = at;
        this.rewardCents = rewardCents;
    }

    /**
     * CONFIRMED -> IN_TRANSIT quando a viagem vinculada começa.
     */
    public void startTransit(Instant at) {
        requireStatus(
                DeliveryStatus.CONFIRMED,
                "Só uma encomenda aceita pode iniciar o transporte."
        );

        status = DeliveryStatus.IN_TRANSIT;
        pickedUpAt = at;
    }

    /**
     * Registra uma tentativa incorreta de confirmação da entrega.
     */
    public void registerConfirmationAttempt() {
        confirmationAttempts++;
    }
    /**
     * PENDING/CONFIRMED/IN_TRANSIT -> CANCELLED.
     *
     * A regra de negÃ³cio do serviÃ§o pode restringir o cancelamento
     * somente aos estados anteriores Ã  coleta.
     */
    public void cancel(String reason) {
        if (status != DeliveryStatus.PENDING
                && status != DeliveryStatus.CONFIRMED
                && status != DeliveryStatus.IN_TRANSIT) {

            throw new IllegalDeliveryStateException(
                    "NÃ£o Ã© possÃ­vel cancelar uma encomenda com status " + status + "."
            );
        }

        status = DeliveryStatus.CANCELLED;
        cancelledAt = Instant.now();
        cancellationReason = reason;
    }

    private void requireStatus(DeliveryStatus expected, String message) {
        if (status != expected) {
            throw new IllegalDeliveryStateException(
                    message + " Status atual: " + status + "."
            );
        }
    }

    public boolean active() {
        return status == DeliveryStatus.PENDING
                || status == DeliveryStatus.CONFIRMED
                || status == DeliveryStatus.IN_TRANSIT;
    }

    // ---------------------------------------------------------
    // Getters e setters
    // ---------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getTrackingCode() {
        return trackingCode;
    }

    public void setTrackingCode(String trackingCode) {
        this.trackingCode = trackingCode;
    }

    public UUID getRideId() {
        return rideId;
    }

    public void setRideId(UUID v) {
        this.rideId = v;
    }

    public String getSenderSub() {
        return senderSub;
    }

    public void setSenderSub(String v) {
        this.senderSub = v;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String v) {
        this.senderName = v;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public void setCompanyId(UUID v) {
        this.companyId = v;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public PackageDetails getPkg() {
        return pkg;
    }

    public void setPkg(PackageDetails v) {
        this.pkg = v;
    }

    public Point getPickupLocation() {
        return pickupLocation;
    }

    public void setPickupLocation(Point v) {
        this.pickupLocation = v;
    }

    public String getPickupAddress() {
        return pickupAddress;
    }

    public void setPickupAddress(String v) {
        this.pickupAddress = v;
    }

    public Point getDropoffLocation() {
        return dropoffLocation;
    }

    public void setDropoffLocation(Point v) {
        this.dropoffLocation = v;
    }

    public String getDropoffAddress() {
        return dropoffAddress;
    }

    public void setDropoffAddress(String v) {
        this.dropoffAddress = v;
    }

    public boolean isFullRoute() {
        return fullRoute;
    }

    public void setFullRoute(boolean v) {
        this.fullRoute = v;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal v) {
        this.price = v;
    }

    public BigDecimal getPlatformFeePercent() {
        return platformFeePercent;
    }

    public void setPlatformFeePercent(BigDecimal v) {
        this.platformFeePercent = v;
    }

    public String getPickupCode() {
        return pickupCode;
    }

    public void setPickupCode(String v) {
        this.pickupCode = v;
    }

    public String getDeliveryKeyword() {
        return deliveryKeyword;
    }

    public void setDeliveryKeyword(String v) {
        this.deliveryKeyword = v;
    }

    public String getConfirmationCode() {
        return confirmationCode;
    }

    public void setConfirmationCode(String confirmationCode) {
        this.confirmationCode = confirmationCode;
    }

    public int getConfirmationAttempts() {
        return confirmationAttempts;
    }

    public void setConfirmationAttempts(int confirmationAttempts) {
        this.confirmationAttempts = confirmationAttempts;
    }

    public Long getRewardCents() {
        return rewardCents;
    }

    public void setRewardCents(Long rewardCents) {
        this.rewardCents = rewardCents;
    }
    public int getFailedCodeAttempts() {
        return failedCodeAttempts;
    }

    public void setFailedCodeAttempts(int v) {
        this.failedCodeAttempts = v;
    }

    public UUID getFreightRequestId() {
        return freightRequestId;
    }

    public void setFreightRequestId(UUID v) {
        this.freightRequestId = v;
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    public void setPaymentIntentId(String v) {
        this.paymentIntentId = v;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getPickedUpAt() {
        return pickedUpAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public DeliveryMode getMode() {
        return mode;
    }

    public void setMode(DeliveryMode mode) {
        this.mode = mode;
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

    public String getOriginAddress() {
        return originAddress;
    }

    public void setOriginAddress(String originAddress) {
        this.originAddress = originAddress;
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

    public String getDestinationAddress() {
        return destinationAddress;
    }

    public void setDestinationAddress(String destinationAddress) {
        this.destinationAddress = destinationAddress;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(BigDecimal distanceKm) {
        this.distanceKm = distanceKm;
    }

    public Long getQuotedPriceCents() {
        return quotedPriceCents;
    }

    public void setQuotedPriceCents(Long quotedPriceCents) {
        this.quotedPriceCents = quotedPriceCents;
    }
    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public String getDriverSub(){
        return driverSub;
    }

    public void setDriverSub(String driverSub){
        this.driverSub = driverSub;
    }

    public UUID getVehicleId(){
        return vehicleId;
    }

    public void setVehicleId(UUID vehicleId){
        this.vehicleId = vehicleId;
    }

    public Instant getAcceptedAt(){
        return acceptedAt;
    }
    public void setAcceptedAt(Instant acceptedAt){
        this.acceptedAt = acceptedAt;
    }

    public String getConfirmedRecipientName() {
        return confirmedRecipientName;
    }

    public void setConfirmedRecipientName(String confirmedRecipientName) {
        this.confirmedRecipientName = confirmedRecipientName;
    }

    public String getDeliveryObservation() {
        return deliveryObservation;
    }

    public void setDeliveryObservation(String deliveryObservation) {
        this.deliveryObservation = deliveryObservation;
    }

    // ---------------------------------------------------------
    // Pagamento (portado da feature/integracao-parte-2)
    // ---------------------------------------------------------

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(PaymentStatus v) {
        this.paymentStatus = v;
    }

    public Long getPaidAmountCents() {
        return paidAmountCents;
    }

    public void setPaidAmountCents(Long v) {
        this.paidAmountCents = v;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant v) {
        this.paidAt = v;
    }

    public Long getDiscountCents() {
        return discountCents;
    }

    public void setDiscountCents(Long v) {
        this.discountCents = v;
    }

    /** Cupom aplicado (null = sem cupom) */
    private UUID couponId;
    @Override public UUID getCouponId() { return couponId; }
    @Override public void setCouponId(UUID v) { this.couponId = v; }
}
