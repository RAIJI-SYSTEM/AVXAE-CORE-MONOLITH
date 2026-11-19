package com.raiji.deliveries.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.raiji.deliveries.Delivery;

public record AvailableDeliveryResponse(
        UUID id,
        String trackingCode,
        String status,
        String mode,
        String originCity,
        String originState,
        String originAddress,
        String destinationCity,
        String destinationState,
        String destinationAddress,
        String description,
        BigDecimal weightKg,
        Integer lengthCm,
        Integer widthCm,
        Integer heightCm,
        String recipientName,
        Instant requestedAt,
        List<RideMatch> compatibleRides
) {
    public record RideMatch(
            UUID id,
            String originCity,
            String originState,
            String destinationCity,
            String destinationState,
            LocalDateTime departureDate
    ) {}

    public static AvailableDeliveryResponse of(
            Delivery delivery,
            List<RideMatch> compatibleRides) {

        return new AvailableDeliveryResponse(
                delivery.getId(),
                delivery.getTrackingCode(),
                delivery.getStatus().name(),
                delivery.getMode() != null ? delivery.getMode().name() : null,
                delivery.getOriginCity(),
                delivery.getOriginState(),
                delivery.getOriginAddress(),
                delivery.getDestinationCity(),
                delivery.getDestinationState(),
                delivery.getDestinationAddress(),
                delivery.getPkg().getDescription(),
                delivery.getPkg().getWeightKg(),
                delivery.getPkg().getLengthCm(),
                delivery.getPkg().getWidthCm(),
                delivery.getPkg().getHeightCm(),
                delivery.getPkg().getRecipientName(),
                delivery.getRequestedAt(),
                compatibleRides
        );
    }
}