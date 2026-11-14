package com.raiji.users.driververification;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Situação da verificação para o app. Não expõe CPF nem nº de registro.
 *
 * @param status NONE (nunca enviou), PROCESSING, APPROVED, REJECTED ou ERROR
 * @param reason motivo em português quando REJECTED/ERROR
 */
public record DriverApplicationResponse(
        String id,
        String status,
        String reason,
        String categoria,
        LocalDate validade,
        Instant createdAt,
        Instant decidedAt) {

    public static DriverApplicationResponse none() {
        return new DriverApplicationResponse(null, "NONE", null, null, null, null, null);
    }

    public static DriverApplicationResponse from(DriverApplication application) {
        return new DriverApplicationResponse(
                String.valueOf(application.getId()),
                application.getStatus().name(),
                application.getReason(),
                application.getCategoria(),
                application.getValidade(),
                application.getCreatedAt(),
                application.getDecidedAt());
    }
}
