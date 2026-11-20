package com.raiji.rides.event;

import java.time.Instant;
import java.util.UUID;

import com.raiji.rides.domain.RideDecision;

/**
 * S2-02 / RF08+RF19 — publicado quando o motorista decide (ACCEPT/REJECT)
 * uma solicitação. Consumido pelo NotificationService (S1-06) para
 * notificar o passageiro.
 */
public record RideDecisionEvent(
        UUID rideId,
        UUID requestId,
        String driverSub,
        String driverName,
        String passengerSub,
        String passengerName,
        RideDecision decision,
        String reason,
        Instant decidedAt,
        /** A vaga tem preço: depois do aceite o passageiro paga pelo app */
        boolean paymentRequired
) {
}
