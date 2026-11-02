package com.raiji.rides.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Payload do POST /api/rides/{rideId}/requests.
 *
 * S2-01 / RF18: a declaração de bagagem passa a ser OBRIGATÓRIA
 * (mínimo 1 item). Sem baggage ou com baggage vazio => 400.
 */
public record RideRequestCreateRequest(

        @NotBlank(message = "O nome do passageiro é obrigatório.")
        @Size(max = 150, message = "O nome deve ter no máximo 150 caracteres.")
        String passengerName,

        @Valid
        @NotEmpty(message = "A declaração de bagagem é obrigatória (mínimo 1 item).")
        List<BaggageItemRequest> baggage

) {}
