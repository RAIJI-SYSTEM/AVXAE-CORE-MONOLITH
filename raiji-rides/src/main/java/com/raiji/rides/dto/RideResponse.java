package com.raiji.rides.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.rides.domain.Ride;

public record RideResponse(
        UUID id,
        String driverSub,
        String driverName,
        UUID vehicleId,
        RideType type,
        String originCity,
        String originState,
        String destinationCity,
        String destinationState,
        LocalDateTime departureDate,
        BigDecimal pricePerPassenger,
        Integer totalSeats,
        Integer availableSeats,
        RideStatus status,
        CargoDetails cargoDetails,
        Instant createdAt,
        Instant updatedAt
) {
    public static RideResponse from(Ride ride) {
        return new RideResponse(
                ride.getId(),
                ride.getDriverSub(),
                ride.getDriverName(),
                ride.getVehicleId(),
                ride.getType(),
                ride.getOriginCity(),
                ride.getOriginState(),
                ride.getDestinationCity(),
                ride.getDestinationState(),
                ride.getDepartureDate(),
                ride.getPricePerPassenger(),
                ride.getTotalSeats(),
                ride.getAvailableSeats(),
                ride.getStatus(),
                ride.getCargoDetails(),
                ride.getCreatedAt(),
                ride.getUpdatedAt()
        );
    }
}
