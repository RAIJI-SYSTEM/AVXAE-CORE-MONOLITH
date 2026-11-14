package com.raiji.rides.dto;

import java.util.List;

import com.raiji.rides.geo.Place;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload do POST /api/rides/{rideId}/requests (pedir vaga).
 *
 * <ul>
 *   <li>baggage é OPCIONAL: vazio/ausente = "sem bagagem". O app pergunta no fim
 *       ("vai levar carga ou mala?") e a pessoa pode editar depois nos detalhes da viagem.</li>
 *   <li>pickup/dropoff (opcionais): embarcar/descer no meio do caminho. O preço vira o
 *       proporcional do trecho. Sem eles = viagem inteira, preço cheio.</li>
 * </ul>
 */
public record RideRequestCreateRequest(
        @NotBlank(message = "O nome do passageiro é obrigatório.")
        @Size(max = 150, message = "O nome deve ter no máximo 150 caracteres.")
        String passengerName,

        @Valid
        @Size(max = 20, message = "No máximo 20 itens de bagagem.")
        List<BaggageItemRequest> baggage,

        @Valid
        Place pickup,

        @Valid
        Place dropoff
) {
    public RideRequestCreateRequest(String passengerName, List<BaggageItemRequest> baggage) {
        this(passengerName, baggage, null, null);
    }
}
