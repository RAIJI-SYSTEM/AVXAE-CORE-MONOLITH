package com.raiji.rides.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import com.raiji.common.enums.PaymentStatus;
import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.notification.NotificationRequested;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pagamento da vaga numa carona: o passageiro paga depois que o motorista aceita (CONFIRMED).
 * O valor é o preço da reserva (proporcional ao trecho), nunca informado pelo app.
 */
@Service
public class RidePaymentService {

    private final RideRepository rides;
    private final RidePassengerRepository passengers;
    private final PaymentService payments;
    private final ApplicationEventPublisher events;

    public RidePaymentService(RideRepository rides, RidePassengerRepository passengers,
                              PaymentService payments, ApplicationEventPublisher events) {
        this.rides = rides;
        this.passengers = passengers;
        this.payments = payments;
        this.events = events;
    }

    /** Passageiro inicia (ou retoma) o pagamento da reserva aceita. */
    @Transactional
    public PaymentResponse start(UUID rideId, UUID requestId, String passengerSub) {
        RidePassenger p = reserva(rideId, requestId);
        Ride ride = carona(rideId);
        if (!p.getPassengerSub().equals(passengerSub)) {
            throw naoEncontrada();
        }
        if (p.getStatus() == RidePassengerStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O motorista ainda não aceitou este pedido.");
        }
        if (p.getStatus() != RidePassengerStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta reserva não está ativa para pagamento.");
        }
        boolean pagaAntes = p.getPaymentStatus() == PaymentStatus.PAID;
        PaymentResponse response = payments.start(p, valor(ride, p),
                "Raiji · vaga " + ride.getOriginCity() + " → " + ride.getDestinationCity(),
                Map.of("type", "RIDE_BOOKING", "rideId", rideId.toString(),
                        "requestId", requestId.toString(), "payerSub", passengerSub),
                "ride-booking:" + requestId);
        passengers.save(p);
        if (!pagaAntes && p.getPaymentStatus() == PaymentStatus.PAID) {
            avisarMotorista(ride, p); // o Stripe confirmou agora, ao retomar o pagamento
        }
        return response;
    }

    /** Situação do pagamento, conferida no Stripe. Visível ao passageiro e ao motorista. */
    @Transactional
    public PaymentResponse status(UUID rideId, UUID requestId, String viewerSub) {
        RidePassenger p = reserva(rideId, requestId);
        Ride ride = carona(rideId);
        if (!p.getPassengerSub().equals(viewerSub) && !ride.getDriverSub().equals(viewerSub)) {
            throw naoEncontrada();
        }
        if (payments.refresh(p)) {
            passengers.save(p);
            avisarMotorista(ride, p);
        }
        return payments.view(p, valor(ride, p));
    }

    /** Webhook do Stripe: atualiza a reserva dona da cobrança (se houver). */
    @EventListener
    @Transactional
    public void onPaymentIntentChanged(PaymentIntentChanged event) {
        passengers.findByPaymentIntentId(event.intent().id()).ifPresent(p -> {
            if (payments.apply(p, event.intent())) {
                passengers.save(p);
                rides.findById(p.getRideId()).ifPresent(ride -> avisarMotorista(ride, p));
            } else {
                passengers.save(p);
            }
        });
    }

    /** Valor da reserva em centavos (preço do trecho; senão, o preço por passageiro). */
    static long valor(Ride ride, RidePassenger p) {
        BigDecimal price = p.getPrice() != null ? p.getPrice() : ride.getPricePerPassenger();
        return PaymentService.toCents(price);
    }

    private void avisarMotorista(Ride ride, RidePassenger p) {
        events.publishEvent(new NotificationRequested(ride.getDriverSub(), "DRIVER", "RIDE_PAYMENT_RECEIVED",
                ride.getId(), p.getId(), p.getPassengerName() + " pagou a vaga na sua carona para "
                + ride.getDestinationCity() + "."));
    }

    private RidePassenger reserva(UUID rideId, UUID requestId) {
        return passengers.findById(requestId)
                .filter(p -> p.getDeletedAt() == null && p.getRideId().equals(rideId))
                .orElseThrow(this::naoEncontrada);
    }

    private Ride carona(UUID rideId) {
        return rides.findById(rideId).orElseThrow(this::naoEncontrada);
    }

    private ResponseStatusException naoEncontrada() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva não encontrada.");
    }
}
