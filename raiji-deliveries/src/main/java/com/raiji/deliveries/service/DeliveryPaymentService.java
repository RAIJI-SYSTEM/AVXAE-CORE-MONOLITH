package com.raiji.deliveries.service;

import java.util.Map;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.PaymentStatus;
import com.raiji.deliveries.Delivery;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.payment.PaymentIntentChanged;
import com.raiji.rides.payment.PaymentResponse;
import com.raiji.rides.payment.PaymentService;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.rewards.CouponChoice;
import com.raiji.rides.rewards.CouponService;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pagamento da encomenda: o remetente (ou alguém da empresa dele) paga depois que o
 * motorista aceita (CONFIRMED). Se cancelar depois de pago, o valor é estornado
 * ({@link DeliveryRefunds}).
 */
@Service
public class DeliveryPaymentService {

    private final DeliveryRepository deliveries;
    private final RideRepository rides;
    private final DeliveryService deliveryService;
    private final PaymentService payments;
    private final DeliveryNotifier notifier;
    private final CouponService coupons;

    public DeliveryPaymentService(DeliveryRepository deliveries, RideRepository rides, DeliveryService deliveryService,
                                  PaymentService payments, DeliveryNotifier notifier, CouponService coupons) {
        this.coupons = coupons;
        this.deliveries = deliveries;
        this.rides = rides;
        this.deliveryService = deliveryService;
        this.payments = payments;
        this.notifier = notifier;
    }

    @Transactional
    public PaymentResponse start(UUID deliveryId, String payerSub, CouponChoice coupon) {
        Delivery d = encomenda(deliveryId);
        Ride ride = rides.findById(d.getRideId()).orElseThrow(this::naoEncontrada);
        if (!deliveryService.podeVerComoRemetente(d, payerSub)) {
            throw naoEncontrada();
        }
        if (d.getStatus() == DeliveryStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O motorista ainda não aceitou esta encomenda.");
        }
        if (d.getStatus() != DeliveryStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta encomenda não está aguardando pagamento.");
        }
        boolean pagaAntes = d.getPaymentStatus() == PaymentStatus.PAID;
        long amount = coupons.amountToCharge(d, payerSub, coupon, valor(d));
        PaymentResponse response = payments.start(d, amount,
                "Raiji · encomenda " + ride.getOriginCity() + " → " + ride.getDestinationCity(),
                Map.of("type", "DELIVERY", "deliveryId", deliveryId.toString(),
                        "rideId", ride.getId().toString(), "payerSub", payerSub),
                "delivery:" + deliveryId);
        deliveries.save(d);
        if (!pagaAntes && d.getPaymentStatus() == PaymentStatus.PAID) {
            avisarMotorista(ride, d);
        }
        return response.withCoupon(d);
    }

    /** Situação do pagamento, conferida no Stripe. Visível ao remetente (e empresa) e ao motorista. */
    @Transactional
    public PaymentResponse status(UUID deliveryId, String viewerSub) {
        Delivery d = encomenda(deliveryId);
        Ride ride = rides.findById(d.getRideId()).orElseThrow(this::naoEncontrada);
        if (!deliveryService.podeVerComoRemetente(d, viewerSub) && !ride.getDriverSub().equals(viewerSub)) {
            throw naoEncontrada();
        }
        if (payments.refresh(d)) {
            deliveries.save(d);
            avisarMotorista(ride, d);
        }
        return payments.view(d, valor(d) - (d.getDiscountCents() == null ? 0 : d.getDiscountCents())).withCoupon(d);
    }

    /** Webhook do Stripe: atualiza a encomenda dona da cobrança (se houver). */
    @EventListener
    @Transactional
    public void onPaymentIntentChanged(PaymentIntentChanged event) {
        deliveries.findByPaymentIntentId(event.intent().id()).ifPresent(d -> {
            boolean pagaAgora = payments.apply(d, event.intent());
            deliveries.save(d);
            if (pagaAgora) {
                rides.findById(d.getRideId()).ifPresent(ride -> avisarMotorista(ride, d));
            }
        });
    }

    static long valor(Delivery d) {
        return PaymentService.toCents(d.getPrice());
    }

    private void avisarMotorista(Ride ride, Delivery d) {
        notifier.driver(ride.getDriverSub(), "DELIVERY_PAYMENT_RECEIVED", ride.getId(), d.getId(),
                (d.getSenderName() == null ? "O remetente" : d.getSenderName())
                        + " pagou a encomenda que vai na sua carona para " + ride.getDestinationCity() + ".");
    }

    private Delivery encomenda(UUID id) {
        return deliveries.findById(id).filter(x -> x.getDeletedAt() == null).orElseThrow(this::naoEncontrada);
    }

    private ResponseStatusException naoEncontrada() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Encomenda não encontrada.");
    }
}
