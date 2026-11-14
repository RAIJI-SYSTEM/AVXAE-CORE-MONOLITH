package com.raiji.rides.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.raiji.common.notification.NotificationRequested;
import com.raiji.rides.dto.NotificationResponse;

import com.raiji.rides.domain.Notification;
import com.raiji.rides.domain.NotificationAudience;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.event.RideCancelledEvent;
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

    /** Aviso genérico vindo de qualquer módulo (encomendas, empresas, frete, ciclo da viagem). */
    @EventListener
    @Transactional
    public void handleNotificationRequested(NotificationRequested event) {
        save(event.recipientSub(), NotificationAudience.valueOf(event.audience()), event.kind(),
                event.rideId(), event.referenceId(), event.message());
    }

    private void save(String recipientSub, NotificationAudience audience, String kind,
                      UUID rideId, UUID referenceId, String message) {
        Notification notification = new Notification();
        notification.setRecipientSub(recipientSub);
        notification.setAudience(audience);
        notification.setKind(kind);
        notification.setRideId(rideId);
        notification.setReferenceId(referenceId);
        notification.setMessage(message.length() > 500 ? message.substring(0, 500) : message);
        notificationRepository.save(notification);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(String recipientSub, NotificationAudience audience) {
        return notificationRepository
                .findTop100ByRecipientSubAndAudienceAndDeletedAtIsNullOrderByCreatedAtDesc(recipientSub, audience)
                .stream().map(NotificationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long countUnread(String recipientSub, NotificationAudience audience) {
        return notificationRepository.countByRecipientSubAndAudienceAndReadAtIsNullAndDeletedAtIsNull(
                recipientSub, audience);
    }

    /** Marca como lidas as notificações informadas (ou todas daquela caixa, se ids vazio). */
    @Transactional
    public int markRead(String recipientSub, NotificationAudience audience, List<UUID> ids) {
        return (ids == null || ids.isEmpty())
                ? notificationRepository.markAllRead(recipientSub, audience, Instant.now())
                : notificationRepository.markRead(recipientSub, ids, Instant.now());
    }

    @EventListener
    @Transactional
    public void handleRideRequestCreated(RideRequestCreatedEvent event) {

        String message = "Nova solicitação de reserva de "
                + event.passengerName()
                + " para sua carona.";

        Notification notification = new Notification();
        notification.setRecipientSub(event.driverSub());
        notification.setAudience(NotificationAudience.DRIVER);
        notification.setKind("RIDE_REQUEST");
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
        notification.setAudience(NotificationAudience.PASSENGER);
        notification.setKind(event.decision() == RideDecision.ACCEPT ? "RIDE_REQUEST_ACCEPTED" : "RIDE_REQUEST_REJECTED");
        notification.setReferenceId(event.requestId());
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

    /**
     * S2-04 / RF09: notifica o MOTORISTA quando uma reserva é cancelada e a
     * vaga é liberada (critério de aceite "Motorista é notificado").
     */
    @EventListener
    @Transactional
    public void handleRideCancelled(RideCancelledEvent event) {

        String message = "A reserva de " + event.passengerName()
                + " na sua carona foi CANCELADA"
                + (event.seatReleased() ? " e a vaga foi liberada." : ".")
                + (event.reason() != null && !event.reason().isBlank()
                        ? " Motivo: " + event.reason()
                        : "");

        // coluna message VARCHAR(500) — defesa contra motivos longos
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }

        Notification notification = new Notification();
        notification.setRecipientSub(event.driverSub());
        notification.setAudience(NotificationAudience.DRIVER);
        notification.setKind("RIDE_BOOKING_CANCELLED");
        notification.setReferenceId(event.requestId());
        notification.setRideId(event.rideId());
        notification.setMessage(message);

        notificationRepository.save(notification);

        log.info(
                "Notificação de cancelamento criada para o motorista {} na carona {} (vaga liberada: {})",
                event.driverSub(),
                event.rideId(),
                event.seatReleased()
        );
    }
}