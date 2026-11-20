package com.raiji.deliveries.dto;

import java.util.UUID;

public record DeliveryAcceptanceRequest (
        UUID rideId,
        UUID vehicleId,
        Long estimatedRewardCents
){}
