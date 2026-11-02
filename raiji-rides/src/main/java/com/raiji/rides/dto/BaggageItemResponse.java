package com.raiji.rides.dto;

import java.math.BigDecimal;

import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.BaggageType;

/**
 * S2-01 / RF18 — Item de bagagem retornado ao motorista
 * (GET /api/rides/{rideId}/requests) e ao solicitante (POST .../requests).
 */
public record BaggageItemResponse(
        BaggageType type,
        Integer quantity,
        Integer lengthCm,
        Integer widthCm,
        Integer heightCm,
        BigDecimal weightKg,
        String description
) {

    public static BaggageItemResponse from(Baggage baggage) {
        return new BaggageItemResponse(
                baggage.getType(),
                baggage.getQuantity(),
                baggage.getLengthCm(),
                baggage.getWidthCm(),
                baggage.getHeightCm(),
                baggage.getWeightKg(),
                baggage.getDescription()
        );
    }
}
