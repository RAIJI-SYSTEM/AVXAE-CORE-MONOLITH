package com.raiji.deliveries.dto;

import java.time.Instant;
import com.raiji.common.enums.DeliveryStatus;

public record DeliveryTimelineResponse(
        DeliveryStatus fromStatus,
        DeliveryStatus toStatus,
        Instant createdAt,
        String actorRole
) {
}