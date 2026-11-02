package com.raiji.rides.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * S2-02 / RF08 — decisão do motorista sobre uma solicitação de vaga.
 *
 * <p>O contrato da API usa maiúsculas ({@code ACCEPT}, {@code REJECT});
 * a entrada aceita qualquer caixa. Valor fora do enum na requisição
 * => erro de desserialização => 400.
 */
public enum RideDecision {

    ACCEPT,
    REJECT;

    @JsonCreator
    public static RideDecision from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (RideDecision decision : values()) {
            if (decision.name().equalsIgnoreCase(value.trim())) {
                return decision;
            }
        }
        throw new IllegalArgumentException(
                "Decisão inválida: '" + value
                        + "'. Valores aceitos: ACCEPT, REJECT."
        );
    }
}
