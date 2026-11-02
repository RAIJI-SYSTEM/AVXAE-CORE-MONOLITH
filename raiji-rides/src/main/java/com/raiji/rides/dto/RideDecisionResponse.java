package com.raiji.rides.dto;

import java.time.Instant;
import java.util.UUID;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.rides.domain.RidePassenger;

/**
 * S2-02 / RF08 — resposta da decisão: {@code {id, status, confirmedAt}}.
 *
 * <p>ACCEPT => {@code CONFIRMED} + {@code confirmedAt} preenchido.
 * REJECT => {@code CANCELLED} + {@code confirmedAt} nulo (status CANCELLED
 * é reutilizado; não há valor REJECTED nem migration nova — decisão da issue).
 */
public record RideDecisionResponse(
        UUID id,
        RidePassengerStatus status,
        Instant confirmedAt
) {

    public static RideDecisionResponse from(RidePassenger passenger) {
        return new RideDecisionResponse(
                passenger.getId(),
                passenger.getStatus(),
                passenger.getConfirmedAt()
        );
    }
}
