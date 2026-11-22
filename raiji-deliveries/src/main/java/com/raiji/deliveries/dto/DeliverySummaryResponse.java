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
        Instant createdAt,
        String confirmationCode
) {

    public static DeliverySummaryResponse of(Delivery delivery) {
        return from(delivery, delivery.getConfirmationCode());
    }

    public static DeliverySummaryResponse forDriver(Delivery delivery) {
        return from(delivery, null);
    }

    private static DeliverySummaryResponse from(
            Delivery delivery,
            String confirmationCode
    ) {
        return new DeliverySummaryResponse(
                delivery.getId(),
                delivery.getTrackingCode(),
                delivery.getStatus(),
                delivery.getMode() != null
                        ? delivery.getMode().name()
                        : null,
                delivery.getDistanceKm(),
                delivery.getQuotedPriceCents(),
                delivery.getRequestedAt(),
                confirmationCode
        );
    }
}