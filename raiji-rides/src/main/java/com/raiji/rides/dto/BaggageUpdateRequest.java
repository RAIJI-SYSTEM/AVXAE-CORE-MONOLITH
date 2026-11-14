package com.raiji.rides.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** PUT .../requests/{id}/baggage — a bagagem nova (lista vazia = sem bagagem). */
public record BaggageUpdateRequest(
        @NotNull(message = "Informe a lista de bagagem (vazia = sem bagagem).")
        @Valid
        @Size(max = 20, message = "No máximo 20 itens de bagagem.")
        List<BaggageItemRequest> baggage
) {
}
