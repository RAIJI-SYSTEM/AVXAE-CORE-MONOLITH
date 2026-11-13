package com.raiji.deliveries.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.domain.PackageDetails;
import com.raiji.deliveries.domain.PackageSize;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.pricing.PriceBreakdown;

/**
 * Encomenda. {@code pickupCode} e {@code deliveryKeyword} só aparecem para quem
 * envia (e os membros da empresa); o motorista recebe null e digita o que ouvir.
 */
public record DeliveryResponse(
        UUID id,
        UUID rideId,
        DeliveryStatus status,
        String senderSub,
        String senderName,
        UUID companyId,
        PackageSize size,
        String description,
        BigDecimal weightKg,
        int lengthCm,
        int widthCm,
        int heightCm,
        boolean fragile,
        BigDecimal declaredValue,
        String recipientName,
        String recipientPhone,
        Double pickupLatitude,
        Double pickupLongitude,
        String pickupAddress,
        Double dropoffLatitude,
        Double dropoffLongitude,
        String dropoffAddress,
        boolean fullRoute,
        BigDecimal price,
        BigDecimal platformFee,
        BigDecimal driverReceives,
        String pickupCode,
        String deliveryKeyword,
        Instant requestedAt,
        Instant confirmedAt,
        Instant pickedUpAt,
        Instant deliveredAt,
        Instant cancelledAt,
        String cancellationReason,
        RideSummary ride
) {
    /** Resumo da carona em que a encomenda viaja. */
    public record RideSummary(UUID id, String driverSub, String driverName, String originCity, String originState,
                              String destinationCity, String destinationState, LocalDateTime departureDate,
                              String status, String meetingPoint) {
        public static RideSummary of(Ride r) {
            return r == null ? null : new RideSummary(r.getId(), r.getDriverSub(), r.getDriverName(),
                    r.getOriginCity(), r.getOriginState(), r.getDestinationCity(), r.getDestinationState(),
                    r.getDepartureDate(), r.getStatus().name(), r.getMeetingPoint());
        }
    }

    public static DeliveryResponse of(Delivery d, Ride ride, boolean showCodes) {
        PackageDetails p = d.getPkg();
        PriceBreakdown price = PriceBreakdown.of(d.getPrice(), d.getPlatformFeePercent());
        return new DeliveryResponse(d.getId(), d.getRideId(), d.getStatus(), d.getSenderSub(), d.getSenderName(),
                d.getCompanyId(), p.getSize(), p.getDescription(), p.getWeightKg(), p.getLengthCm(), p.getWidthCm(),
                p.getHeightCm(), p.isFragile(), p.getDeclaredValue(), p.getRecipientName(), p.getRecipientPhone(),
                GeoSupport.latitude(d.getPickupLocation()), GeoSupport.longitude(d.getPickupLocation()),
                d.getPickupAddress(),
                GeoSupport.latitude(d.getDropoffLocation()), GeoSupport.longitude(d.getDropoffLocation()),
                d.getDropoffAddress(), d.isFullRoute(), price.price(), price.platformFee(), price.driverReceives(),
                showCodes ? d.getPickupCode() : null, showCodes ? d.getDeliveryKeyword() : null,
                d.getRequestedAt(), d.getConfirmedAt(), d.getPickedUpAt(), d.getDeliveredAt(), d.getCancelledAt(),
                d.getCancellationReason(), RideSummary.of(ride));
    }
}
