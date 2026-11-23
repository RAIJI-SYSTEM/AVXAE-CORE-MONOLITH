package com.raiji.rides.payout;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Um valor recebido pela plataforma que pertence (menos a comissão) a um motorista:
 * uma vaga paga de viagem concluída, ou uma encomenda paga e entregue.
 *
 * @param kind RIDE (vaga) ou DELIVERY (encomenda)
 */
public record PayoutItem(
        String kind,
        UUID id,
        UUID rideId,
        String driverSub,
        String driverName,
        String description,
        LocalDateTime departureDate,
        Instant paidAt,
        long paidCents,
        long feeCents,
        long driverCents) {
}
