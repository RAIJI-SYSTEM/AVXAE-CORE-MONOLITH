package com.raiji.rides.event;

import java.util.UUID;

/**
 * A carona mudou de um jeito que interessa ao grupo do chat (terminou, foi cancelada).
 * Entradas e saídas de passageiros já vêm por {@link RideDecisionEvent} e {@link RideCancelledEvent}.
 */
public record RideChatChanged(UUID rideId) {
}
