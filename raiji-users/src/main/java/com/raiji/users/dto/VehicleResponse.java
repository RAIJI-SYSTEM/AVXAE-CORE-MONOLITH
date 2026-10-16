package com.raiji.users.dto;

import java.util.UUID;

public record VehicleResponse(
        UUID id,
        String ownerSub,
        String plate,
        String type,
        String model,
        String color,
        Integer year,
        Integer passengerSeats,
        Integer cargoVolumeLiters,
        boolean active
) {}