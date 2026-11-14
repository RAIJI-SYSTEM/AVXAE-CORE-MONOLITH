package com.raiji.deliveries.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** O código de coleta ou a palavra-chave de entrega digitada pelo motorista. */
public record CodeRequest(@NotBlank @Size(max = 20) String code) {
}
