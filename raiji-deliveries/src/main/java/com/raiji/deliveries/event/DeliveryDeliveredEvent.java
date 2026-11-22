package com.raiji.deliveries.event;

import java.time.Instant;
import java.util.UUID;

public record DeliveryDeliveredEvent(
        UUID deliveryId,
        UUID rideId,
        String senderSub,
        String driverSub,
        Long rewardCents,
        Instant deliveredAt
) {
}