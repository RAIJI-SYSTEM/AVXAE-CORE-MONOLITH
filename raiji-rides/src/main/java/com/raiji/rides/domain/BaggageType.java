package com.raiji.rides.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * S2-01 / RF18 — Tipos de bagagem aceitos na declaração.
 *
 * <p>O contrato da API usa códigos em minúsculas ({@code mochila}, {@code sacola},
 * {@code mala}, {@code caixa}); o banco armazena o {@code name()} do enum
 * (VARCHAR). Valor fora do enum na requisição => erro de desserialização => 400.
 */
public enum BaggageType {

    MOCHILA,
    SACOLA,
    MALA,
    CAIXA;

    @JsonValue
    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static BaggageType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BaggageType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Tipo de bagagem inválido: '" + code
                        + "'. Valores aceitos: mochila, sacola, mala, caixa."
        );
    }
}
