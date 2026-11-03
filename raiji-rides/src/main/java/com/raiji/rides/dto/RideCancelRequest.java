package com.raiji.rides.dto;

import jakarta.validation.constraints.Size;

/**
 * S2-04 / RF09 — payload OPCIONAL do
 * {@code DELETE /api/rides/{rideId}/requests/{requestId}}.
 *
 * <pre>{@code
 * { "reason": "Imprevisto pessoal" }
 * }</pre>
 *
 * <p>O corpo pode ser omitido por completo (cancelamento sem motivo). Quando
 * presente, {@code reason} tem no máximo 500 caracteres (mesmo limite do
 * {@code cancellation_reason} usado no REJECT da S2-02).
 */
public record RideCancelRequest(

        @Size(max = 500, message = "O motivo deve ter no máximo 500 caracteres.")
        String reason
) {
}
