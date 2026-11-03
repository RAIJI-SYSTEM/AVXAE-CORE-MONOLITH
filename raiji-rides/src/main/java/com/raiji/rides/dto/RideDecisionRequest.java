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
        String reason,

        /**
         * S2-05 / RN04 — PaymentIntent do Stripe da carona paga. Opcional;
         * quando presente no ACCEPT, é gravado na reserva para permitir o
         * estorno automático no cancelamento. (A integração de pagamento
         * completa fica para sprint futura — por ora aceitamos no payload.)
         */
        @Size(max = 100, message = "paymentIntentId deve ter no máximo 100 caracteres.")
        String paymentIntentId
) {

    @AssertTrue(message = "O motivo (reason) é obrigatório ao recusar (REJECT).")
    public boolean hasReasonWhenReject() {
        return decision != RideDecision.REJECT
                || (reason != null && !reason.isBlank());
    }
}
