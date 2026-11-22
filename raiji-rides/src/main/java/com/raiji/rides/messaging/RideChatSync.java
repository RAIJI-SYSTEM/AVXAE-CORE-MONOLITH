package com.raiji.rides.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.event.RideCancelledEvent;
import com.raiji.rides.event.RideChatChanged;
import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mantém o grupo da carona no chat: a cada aceite, cancelamento, fim ou cancelamento da
 * viagem, manda a situação atual ({@link RideChatSnapshot}) depois do commit. Ao subir,
 * reenvia as caronas ativas (corrige o que tiver se perdido com o RabbitMQ fora do ar).
 * Falha de envio só vira log: o chat em grupo nunca impede reservar ou viajar.
 */
@Component
@ConditionalOnProperty(name = "raiji.messaging.enabled", havingValue = "true")
public class RideChatSync {

    private static final Logger log = LoggerFactory.getLogger(RideChatSync.class);

    private final RideRepository rides;
    private final RidePassengerRepository passengers;
    private final RabbitTemplate rabbit;
    private final Clock clock;

    public RideChatSync(RideRepository rides, RidePassengerRepository passengers, RabbitTemplate rabbit, Clock clock) {
        this.rides = rides;
        this.passengers = passengers;
        this.rabbit = rabbit;
        this.clock = clock;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onDecision(RideDecisionEvent event) {
        sync(event.rideId());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onCancelled(RideCancelledEvent event) {
        sync(event.rideId());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onChanged(RideChatChanged event) {
        sync(event.rideId());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resyncActiveRides() {
        try {
            List<Ride> active = rides.findByStatusInAndDeletedAtIsNull(List.of(RideStatus.PLANNED, RideStatus.IN_PROGRESS));
            active.forEach(this::send);
            log.info("Grupos do chat: {} caronas ativas sincronizadas", active.size());
        } catch (RuntimeException e) {
            log.warn("Não foi possível sincronizar os grupos do chat ao subir: {}", e.getMessage());
        }
    }

    void sync(UUID rideId) {
        rides.findById(rideId).ifPresent(this::send);
    }

    private void send(Ride ride) {
        try {
            RideChatSnapshot snapshot = RideChatSnapshot.of(ride, passengers.findByRideIdAndDeletedAtIsNull(ride.getId()),
                    Instant.now(clock));
            rabbit.convertAndSend(MessagingConfig.RIDES_EXCHANGE, MessagingConfig.RIDE_SYNCED, snapshot);
        } catch (AmqpException e) {
            log.warn("Grupo do chat da carona {} não sincronizado: {}", ride.getId(), e.getMessage());
        }
    }
}
