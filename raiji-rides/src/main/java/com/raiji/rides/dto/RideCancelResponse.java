package com.raiji.rides.dto;

import java.time.Instant;
import java.util.UUID;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.rides.domain.RidePassenger;

/**
 * S2-04 / RF09 — resposta do cancelamento de reserva.
 *
 * <p>Espelha os critérios de aceite: a reserva vai para {@code CANCELLED} com
 * {@code cancellationReason} e {@code cancelledAt} (a "data"), e
 * {@code seatReleased} indica se a vaga foi devolvida à carona
 * ({@code true} para reserva CONFIRMED, {@code false} para PENDING).
 */
public record RideCancelResponse(
        UUID id,
        UUID rideId,
        RidePassengerStatus status,
        Instant cancelledAt,
        String cancellationReason,
        boolean seatReleased
) {

    public static RideCancelResponse from(RidePassenger passenger, boolean seatReleased) {
        return new RideCancelResponse(
                passenger.getId(),
                passenger.getRideId(),
                passenger.getStatus(),
                passenger.getCancelledAt(),
                passenger.getCancellationReason(),
                seatReleased
        );
    }
}
