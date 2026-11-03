package com.raiji.rides.web;

import java.util.UUID;

import com.raiji.rides.dto.RideCancelRequest;
import com.raiji.rides.dto.RideCancelResponse;
import com.raiji.rides.dto.RideDecisionRequest;
import com.raiji.rides.dto.RideDecisionResponse;
import com.raiji.rides.service.RidePassengerService;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * S2-02 / RF08+RF19 — aceite/recusa do motorista sobre as solicitações,
 * decididos APÓS visualizar a bagagem declarada (GET da S1-06/S2-01).
 *
 * <p>Compartilha o base path com o RideRequestController (POST/GET);
 * o PATCH /{requestId} é exclusivo deste controller — sem colisão de rota.
 */
@RestController
@RequestMapping("/api/rides/{rideId}/requests")
public class RidePassengerController {

    private final RidePassengerService ridePassengerService;

    public RidePassengerController(
            RidePassengerService ridePassengerService
    ) {
        this.ridePassengerService = ridePassengerService;
    }

    /** 200 => {id, status, confirmedAt}. reason obrigatório no REJECT. */
    @PatchMapping("/{requestId}")
    @PreAuthorize("hasRole('DRIVER')")
    public RideDecisionResponse decide(
            @PathVariable UUID rideId,
            @PathVariable UUID requestId,
            @Valid @RequestBody RideDecisionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ridePassengerService.decide(
                rideId,
                requestId,
                jwt.getSubject(),
                request.decision(),
                request.reason()
        );
    }

    /**
     * S2-04 / RF09 + RN06 — cancela a reserva e libera a vaga imediatamente.
     *
     * <p>Aceita o passageiro dono da reserva OU o motorista dono da carona; a
     * checagem fina de posse acontece no service (403 quando não pertence).
     * O corpo {@code { "reason": "..." }} é OPCIONAL.
     *
     * <p>200 =&gt; {@code {id, rideId, status=CANCELLED, cancelledAt,
     * cancellationReason, seatReleased}}.
     */
    @DeleteMapping("/{requestId}")
    @PreAuthorize("hasAnyRole('PASSENGER', 'DRIVER')")
    public RideCancelResponse cancel(
            @PathVariable UUID rideId,
            @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) RideCancelRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ridePassengerService.cancel(
                rideId,
                requestId,
                jwt.getSubject(),
                request == null ? null : request.reason()
        );
    }
}
