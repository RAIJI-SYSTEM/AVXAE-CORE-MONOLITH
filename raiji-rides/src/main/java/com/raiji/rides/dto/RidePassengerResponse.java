package com.raiji.rides.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.raiji.rides.domain.RidePassenger;
import com.raiji.common.enums.RidePassengerStatus;

/**
 * Resposta de solicitação de vaga.
 * S2-01 / RF18: inclui a bagagem declarada — o motorista a vê ANTES de
 * aceitar/recusar (GET /api/rides/{rideId}/requests).
 */
public record RidePassengerResponse(
        UUID id,
        UUID rideId,
        String passengerSub,
        String passengerName,
        RidePassengerStatus status,
        Instant joinedAt,
        List<BaggageItemResponse> baggage
) {

    public static RidePassengerResponse from(RidePassenger passenger) {
        List<BaggageItemResponse> baggage = passenger.getBaggage() == null
                ? List.of()
                : passenger.getBaggage().stream()
                        .map(BaggageItemResponse::from)
                        .toList();

        return new RidePassengerResponse(
                passenger.getId(),
                passenger.getRideId(),
                passenger.getPassengerSub(),
                passenger.getPassengerName(),
                passenger.getStatus(),
                passenger.getJoinedAt(),
                baggage
        );
    }
}
