package com.raiji.rides.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RideType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload de criação de carona (issue S1-04 / RF03).
 *
 * - pricePerPassenger == null => carona gratuita
 * - pricePerPassenger > 0    => carona paga
 * - cargoDetails é obrigatório quando type ∈ {CARGO, MIXED} (validado no service)
 * - departureDate: agora + 5min ≤ departureDate ≤ agora + 3 meses (RN10)
 */
public record RideRequest(
        @NotNull
        UUID vehicleId,

        @NotNull
        RideType type,

        @NotBlank
        @Size(max = 100)
        String originCity,

        @NotBlank
        @Pattern(regexp = "[A-Za-z]{2}", message = "originState deve ter 2 letras")
        String originState,

        @NotBlank
        @Size(max = 100)
        String destinationCity,

        @NotBlank
        @Pattern(regexp = "[A-Za-z]{2}", message = "destinationState deve ter 2 letras")
        String destinationState,

        @NotNull
        LocalDateTime departureDate,

        BigDecimal pricePerPassenger,

        @NotNull
        @Min(1)
        Integer totalSeats,

        @Valid
        CargoDetails cargoDetails
) {}
