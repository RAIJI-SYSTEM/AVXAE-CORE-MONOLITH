package com.raiji.deliveries.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.Delivery;

public record DeliverySummaryResponse(
        UUID id,
        String trackingCode,
        DeliveryStatus status,
        String mode,
        BigDecimal distanceKm,
        Long quotedPriceCents,
        Instant createdAt
) {

    public static DeliverySummaryResponse of(Delivery delivery) {
        return new DeliverySummaryResponse(
                delivery.getId(),
                delivery.getTrackingCode(),
                delivery.getStatus(),
                delivery.getMode() != null
                        ? delivery.getMode().name()
                        : null,
                delivery.getDistanceKm(),
                delivery.getQuotedPriceCents(),
                delivery.getRequestedAt()
        );
    }
}