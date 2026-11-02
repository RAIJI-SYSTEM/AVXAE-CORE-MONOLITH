package com.raiji.rides.dto;

import com.raiji.rides.domain.RideDecision;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * S2-02 / RF08 — payload do PATCH /api/rides/{rideId}/requests/{requestId}.
 *
 * <p>{@code reason} é obrigatório quando {@code decision = REJECT}
 * (validação cross-field com @AssertTrue, mesmo padrão do
 * UpdateProfileRequest.hasAtLeastOneField()).
 */
public record RideDecisionRequest(

        @NotNull(message = "A decisão é obrigatória (ACCEPT ou REJECT).")
        RideDecision decision,

        @Size(max = 500, message = "O motivo deve ter no máximo 500 caracteres.")
        String reason
) {

    @AssertTrue(message = "O motivo (reason) é obrigatório ao recusar (REJECT).")
    public boolean hasReasonWhenReject() {
        return decision != RideDecision.REJECT
                || (reason != null && !reason.isBlank());
    }
}
