package com.raiji.deliveries.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** O motorista aceita (ACCEPT) ou recusa (REJECT, com motivo) a encomenda. */
public record DeliveryDecisionRequest(
        @NotNull Decision decision,
        @Size(max = 500) String reason
) {
    public enum Decision { ACCEPT, REJECT }
}
