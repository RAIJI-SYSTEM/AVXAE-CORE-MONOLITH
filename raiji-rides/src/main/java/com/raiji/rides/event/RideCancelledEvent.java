package com.raiji.rides.event;

import java.time.Instant;
import java.util.UUID;

/**
 * S2-04 / RF09 + RN06 — publicado quando uma reserva é cancelada ANTES da
 * viagem (pelo próprio passageiro ou pelo motorista dono da carona).
 *
 * <p>Consumido pelo {@code NotificationService} para notificar o MOTORISTA
 * de que a vaga foi liberada (critério de aceite "Motorista é notificado").
 *
 * @param seatReleased {@code true} quando a reserva estava CONFIRMED e a
 *                     vaga foi devolvida à carona ({@code availableSeats++});
 *                     {@code false} quando estava PENDING (nenhuma vaga ocupada).
 */
public record RideCancelledEvent(
        UUID rideId,
        UUID requestId,
        String driverSub,
        String driverName,
        String passengerSub,
        String passengerName,
        String reason,
        Instant cancelledAt,
        boolean seatReleased
) {
}
