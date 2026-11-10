package com.raiji.rides.dto;


import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record RideIntentCreateRequest(
        @NotNull
        RideIntentType type,

        @NotNull
        RideIntentMode mode,

        @NotBlank
        @Size(max = 100)
        String originCity,

        @NotBlank
        @Pattern(regexp = "[A-Za-z]{2}")
        String originState,

        @NotNull
        Double originLatitude,

        @NotNull
        Double originLongitude,

        @Size(max = 100)
        String destinationCity,

        @Pattern(regexp = "[A-Za-z]{2}")
        String destinationState,

        Double destinationLatitude,

        Double destinationLongitude,

        OffsetDateTime desiredAt


) {
}
