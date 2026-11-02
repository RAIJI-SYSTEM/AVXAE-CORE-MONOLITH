package com.raiji.rides.dto;

import com.raiji.rides.domain.BaggageType;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * S2-01 / RF18 — Item de bagagem no payload da solicitação de vaga.
 *
 * Regras (critérios de aceite):
 * - type ∈ {mochila, sacola, mala, caixa}  -> fora do enum => 400
 * - quantity >= 1                          -> <= 0         => 400
 * - lengthCm/widthCm/heightCm > 0          -> <= 0         => 400
 */
public record BaggageItemRequest(

        @NotNull(message = "O tipo da bagagem é obrigatório (mochila, sacola, mala, caixa).")
        BaggageType type,

        @NotNull(message = "A quantidade é obrigatória.")
        @Min(value = 1, message = "A quantidade deve ser maior ou igual a 1.")
        Integer quantity,

        @NotNull(message = "O comprimento (lengthCm) é obrigatório.")
        @Positive(message = "O comprimento deve ser maior que zero.")
        Integer lengthCm,

        @NotNull(message = "A largura (widthCm) é obrigatória.")
        @Positive(message = "A largura deve ser maior que zero.")
        Integer widthCm,

        @NotNull(message = "A altura (heightCm) é obrigatória.")
        @Positive(message = "A altura deve ser maior que zero.")
        Integer heightCm,

        @Positive(message = "O peso (weightKg) deve ser maior que zero quando informado.")
        @Digits(integer = 8, fraction = 2, message = "O peso deve ter no máximo 8 dígitos e 2 casas decimais.")
        BigDecimal weightKg,

        @Size(max = 500, message = "A descrição deve ter no máximo 500 caracteres.")
        String description

) {}
