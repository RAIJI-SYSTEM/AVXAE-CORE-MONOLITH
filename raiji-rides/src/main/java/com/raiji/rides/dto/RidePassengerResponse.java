package com.raiji.rides.dto;

import java.time.Instant;
import java.util.UUID;

import com.raiji.rides.domain.RidePassenger;
import com.raiji.common.enums.RidePassengerStatus;

public record RidePassengerResponse(
        UUID id,
        UUID rideId,
        String passengerSub,
        String passengerName,
        RidePassengerStatus status,
        Instant joinedAt
) {

    public static RidePassengerResponse from(RidePassenger passenger) {
        return new RidePassengerResponse(
                passenger.getId(),
                passenger.getRideId(),
                passenger.getPassengerSub(),
                passenger.getPassengerName(),
                passenger.getStatus(),
                passenger.getJoinedAt()
        );
    }
}