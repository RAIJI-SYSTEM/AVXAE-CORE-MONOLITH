package com.raiji.users.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public  record VehicleRequest(
        @NotBlank
        @Size(max = 7)
        String plate,

        @NotBlank
        @Size(max = 50)
        String type,

        @NotBlank
        @Size(max = 100)
        String model,

        @Size(max = 50)
        String color,

        @Min(1900)
        Integer year,

        @Min(1)
        Integer passengerSeats,

        @Min(0)
        Integer cargoVolumeLiters
) {}
