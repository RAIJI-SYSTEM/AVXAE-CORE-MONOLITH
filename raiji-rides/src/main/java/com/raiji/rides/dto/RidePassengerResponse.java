package com.raiji.rides.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.geo.GeoSupport;

/**
 * Pedido de vaga. Inclui a bagagem declarada — o motorista a vê ANTES de
 * aceitar/recusar — e, se houver, a alteração de bagagem esperando aprovação.
 */
public record RidePassengerResponse(
        UUID id,
        UUID rideId,
        String passengerSub,
        String passengerName,
        RidePassengerStatus status,
        Instant joinedAt,
        List<BaggageItemResponse> baggage,
        /** Alteração de bagagem esperando o motorista aprovar (vazia se não houver). */
        List<BaggageItemResponse> pendingBaggage,
        boolean fullRoute,
        BigDecimal price,
        Double pickupLatitude,
        Double pickupLongitude,
        String pickupAddress,
        Double dropoffLatitude,
        Double dropoffLongitude,
        String dropoffAddress,
        Instant confirmedAt,
        Instant cancelledAt,
        String cancellationReason
) {

    public static RidePassengerResponse from(RidePassenger p) {
        return new RidePassengerResponse(
                p.getId(),
                p.getRideId(),
                p.getPassengerSub(),
                p.getPassengerName(),
                p.getStatus(),
                p.getJoinedAt(),
                items(p.getBaggage() == null ? List.of() : p.activeBaggage()),
                items(p.getBaggage() == null ? List.of() : p.pendingBaggage()),
                p.isFullRoute(),
                p.getPrice(),
                GeoSupport.latitude(p.getPickupLocation()),
                GeoSupport.longitude(p.getPickupLocation()),
                p.getPickupAddress(),
                GeoSupport.latitude(p.getDropoffLocation()),
                GeoSupport.longitude(p.getDropoffLocation()),
                p.getDropoffAddress(),
                p.getConfirmedAt(),
                p.getCancelledAt(),
                p.getCancellationReason()
        );
    }

    private static List<BaggageItemResponse> items(List<Baggage> list) {
        return list.stream().map(BaggageItemResponse::from).toList();
    }
}
