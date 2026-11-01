package com.raiji.rides.event;

import java.time.Instant;
import java.util.UUID;

public record RideRequestCreatedEvent(
        UUID rideId,
        String driverSub,
        String driverName,
        String passengerSub,
        String passengerName,
        Instant joinedAt
) {}