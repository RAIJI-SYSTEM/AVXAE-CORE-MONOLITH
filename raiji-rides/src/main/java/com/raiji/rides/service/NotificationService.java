package com.raiji.rides.service;

import com.raiji.rides.domain.Notification;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.event.RideRequestCreatedEvent;
import com.raiji.rides.repository.NotificationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;

@Service
public class NotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;

    public NotificationService(
            NotificationRepository notificationRepository
    ) {
        this.notificationRepository = notificationRepository;
    }

    @EventListener
    @Transactional
    public void handleRideRequestCreated(RideRequestCreatedEvent event) {

        String message = "Nova solicitação de reserva de "
                + event.passengerName()
                + " para sua carona.";

        Notification notification = new Notification();
        notification.setRecipientSub(event.driverSub());
        notification.setRideId(event.rideId());
        notification.setMessage(message);

        notificationRepository.save(notification);

        log.info(
                "Notificação criada para o motorista {} referente à carona {}",
                event.driverSub(),
                event.rideId()
        );
    }

    /**
     * S2-02 / RF19: notifica o passageiro sobre a decisão do motorista
     * (aceite ou recusa com o motivo declarado).
     */
    @EventListener
    @Transactional
    public void handleRideDecision(RideDecisionEvent event) {

        String message = event.decision() == RideDecision.ACCEPT
                ? "Sua solicitação de carona com " + event.driverName()
                        + " foi ACEITA."
                : "Sua solicitação de carona com " + event.driverName()
                        + " foi RECUSADA. Motivo: " + event.reason();

        // coluna message VARCHAR(500) — defesa contra motivos longos
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }

        Notification notification = new Notification();
        notification.setRecipientSub(event.passengerSub());
        notification.setRideId(event.rideId());
        notification.setMessage(message);

        notificationRepository.save(notification);

        log.info(
                "Notificação de decisão ({}) criada para o passageiro {} na carona {}",
                event.decision(),
                event.passengerSub(),
                event.rideId()
        );
    }
}