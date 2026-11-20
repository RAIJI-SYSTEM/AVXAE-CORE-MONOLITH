package com.raiji.deliveries.dto;

import java.time.Instant;
import java.util.UUID;

import com.raiji.deliveries.Delivery;

public record DeliveryAcceptanceResponse(
        UUID id,
        String status,
        String mode,
        String driverSub,
        UUID rideId,
        UUID vehicleId,
        Instant acceptedAt
) {

    public static DeliveryAcceptanceResponse of(Delivery delivery) {
        return new DeliveryAcceptanceResponse(
                delivery.getId(),
                delivery.getStatus().name(),
                delivery.getMode() != null
                        ? delivery.getMode().name()
                        : null,
                delivery.getDriverSub(),
                delivery.getRideId(),
                delivery.getVehicleId(),
                delivery.getAcceptedAt()
        );
    }
}