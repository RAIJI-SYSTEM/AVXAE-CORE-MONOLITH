package com.raiji.rides.dto;

import java.util.List;
import java.util.UUID;

/** Resultado do "aceitar todos": quem entrou e quem ficou de fora (e por quê). */
public record AcceptAllResponse(List<UUID> accepted, List<Skipped> skipped, int availableSeats) {
    public record Skipped(UUID requestId, String reason) {}
}
