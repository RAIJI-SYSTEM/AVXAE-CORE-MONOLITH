package com.raiji.deliveries.freight;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.deliveries.domain.PackageDetails;
import com.raiji.deliveries.domain.PackageSize;
import com.raiji.deliveries.dto.PackageRequest;
import com.raiji.rides.geo.GeoSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Contratos HTTP das propostas de frete. */
public final class FreightDtos {

    private FreightDtos() {
    }

    /** Cidade + ponto no mapa. */
    public record FreightPlace(
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "UF com 2 letras") String state,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Size(max = 255) String address) {}

    /**
     * Nova proposta. Com {@code targetDriverSub} = direta (offeredPrice obrigatório);
     * sem = pública (offeredPrice opcional, só de referência).
     */
    public record CreateFreightRequest(
            @Size(max = 64) String targetDriverSub,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal offeredPrice,
            @NotNull @Valid FreightPlace origin,
            @NotNull @Valid FreightPlace destination,
            @NotNull LocalDateTime desiredDate,
            @NotNull @Valid PackageRequest pkg,
            @Size(max = 500) String notes) {}

    public record CreateOfferRequest(
            @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal price,
            @NotNull UUID vehicleId,
            @Size(max = 500) String message) {}

    public record AcceptDirectRequest(@NotNull UUID vehicleId) {}

    public record DeclineRequest(@Size(max = 500) String reason) {}

    public record OfferResponse(UUID id, UUID requestId, String driverSub, String driverName, UUID vehicleId,
                                BigDecimal price, String message, OfferStatus status, Instant createdAt) {
        static OfferResponse of(FreightOffer o) {
            return new OfferResponse(o.getId(), o.getRequestId(), o.getDriverSub(), o.getDriverName(),
                    o.getVehicleId(), o.getPrice(), o.getMessage(), o.getStatus(), o.getCreatedAt());
        }
    }

    /**
     * Proposta. Nome e telefone de quem recebe só aparecem para a empresa (o motorista
     * vê na encomenda, depois de fechado o frete).
     */
    public record FreightResponse(
            UUID id, UUID companyId, String companyName, boolean direct, String targetDriverSub, FreightStatus status,
            String originCity, String originState, Double originLatitude, Double originLongitude, String originAddress,
            String destinationCity, String destinationState, Double destinationLatitude, Double destinationLongitude,
            String destinationAddress, LocalDateTime desiredDate,
            PackageSize size, String description, BigDecimal weightKg, int lengthCm, int widthCm, int heightCm,
            boolean fragile, BigDecimal declaredValue, String recipientName, String recipientPhone,
            BigDecimal offeredPrice, String notes, long pendingOffers, OfferResponse myOffer,
            UUID rideId, UUID deliveryId, String declineReason, Instant createdAt, Instant closedAt) {

        static FreightResponse of(FreightRequest f, String companyName, boolean companyView,
                                  long pendingOffers, OfferResponse myOffer) {
            PackageDetails p = f.getPkg();
            return new FreightResponse(f.getId(), f.getCompanyId(), companyName, f.direct(),
                    companyView ? f.getTargetDriverSub() : null, f.getStatus(),
                    f.getOriginCity(), f.getOriginState(), GeoSupport.latitude(f.getOriginLocation()),
                    GeoSupport.longitude(f.getOriginLocation()), f.getOriginAddress(),
                    f.getDestinationCity(), f.getDestinationState(), GeoSupport.latitude(f.getDestinationLocation()),
                    GeoSupport.longitude(f.getDestinationLocation()), f.getDestinationAddress(), f.getDesiredDate(),
                    p.getSize(), p.getDescription(), p.getWeightKg(), p.getLengthCm(), p.getWidthCm(), p.getHeightCm(),
                    p.isFragile(), p.getDeclaredValue(),
                    companyView ? p.getRecipientName() : null, companyView ? p.getRecipientPhone() : null,
                    f.getOfferedPrice(), f.getNotes(), pendingOffers, myOffer,
                    companyView ? f.getRideId() : null, companyView ? f.getDeliveryId() : null,
                    f.getDeclineReason(), f.getCreatedAt(), f.getClosedAt());
        }
    }
}
