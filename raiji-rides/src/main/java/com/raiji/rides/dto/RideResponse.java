package com.raiji.rides.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.pricing.PriceBreakdown;

public record RideResponse(
        UUID id,
        String driverSub,
        String driverName,
        UUID vehicleId,
        RideType type,
        String originCity,
        String originState,
        String destinationCity,
        String destinationState,
        LocalDateTime departureDate,
        BigDecimal pricePerPassenger,
        /** Comissão da plataforma (%) desta carona. */
        BigDecimal platformFeePercent,
        /** Parte do preço de cada vaga que fica com a plataforma (null se gratuita). */
        BigDecimal platformFeePerPassenger,
        /** Quanto o motorista recebe por vaga (null se gratuita). */
        BigDecimal driverReceivesPerPassenger,
        Integer totalSeats,
        Integer availableSeats,
        RideStatus status,
        CargoDetails cargoDetails,
        Instant createdAt,
        Instant updatedAt,
        Double originLatitude,
        Double originLongitude,
        String originAddress,
        Double destinationLatitude,
        Double destinationLongitude,
        String destinationAddress,
        String meetingPoint,
        Integer routeDistanceMeters,
        RideVisibility visibility,
        boolean bookingsClosed,
        Instant startedAt,
        Instant completedAt,
        BigDecimal pricePerPackage
) {
    public static RideResponse from(Ride ride) {
        PriceBreakdown price = PriceBreakdown.of(ride.getPricePerPassenger(), ride.getPlatformFeePercent());
        return new RideResponse(
                ride.getId(),
                ride.getDriverSub(),
                ride.getDriverName(),
                ride.getVehicleId(),
                ride.getType(),
                ride.getOriginCity(),
                ride.getOriginState(),
                ride.getDestinationCity(),
                ride.getDestinationState(),
                ride.getDepartureDate(),
                ride.getPricePerPassenger(),
                price.platformFeePercent(),
                price.platformFee(),
                price.driverReceives(),
                ride.getTotalSeats(),
                ride.getAvailableSeats(),
                ride.getStatus(),
                ride.getCargoDetails(),
                ride.getCreatedAt(),
                ride.getUpdatedAt(),
                GeoSupport.latitude(ride.getOriginLocation()),
                GeoSupport.longitude(ride.getOriginLocation()),
                ride.getOriginAddress(),
                GeoSupport.latitude(ride.getDestinationLocation()),
                GeoSupport.longitude(ride.getDestinationLocation()),
                ride.getDestinationAddress(),
                ride.getMeetingPoint(),
                ride.getRouteDistanceMeters(),
                ride.getVisibility(),
                ride.isBookingsClosed(),
                ride.getStartedAt(),
                ride.getCompletedAt(),
                ride.getPricePerPackage()
        );
    }
}
