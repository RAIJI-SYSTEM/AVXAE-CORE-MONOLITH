package com.raiji.deliveries.board;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.deliveries.domain.PackageSize;
import com.raiji.deliveries.dto.PackageRequest;
import com.raiji.deliveries.freight.FreightDtos.FreightPlace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Contratos HTTP do mural. */
public final class BoardDtos {

    private BoardDtos() {
    }

    /** pkg obrigatório em PACKAGE; companyId só em PACKAGE (publicar em nome da empresa). */
    public record CreateBoardRequest(
            @NotNull BoardKind kind,
            @NotNull @Valid FreightPlace origin,
            @NotNull @Valid FreightPlace destination,
            @NotNull LocalDateTime desiredAt,
            @Valid PackageRequest pkg,
            UUID companyId,
            @Size(max = 500) String notes) {}

    /** rideId = levar numa carona que já publiquei; vehicleId = viagem nova. Exatamente um dos dois. */
    public record CreateBoardOffer(
            @NotNull(message = "Informe o valor.")
            @DecimalMin(value = "1.00", message = "O valor mínimo é R$ 1,00.")
            @DecimalMax(value = "99999.99", message = "Valor alto demais.")
            @Digits(integer = 5, fraction = 2, message = "Valor inválido.") BigDecimal price,
            UUID rideId,
            UUID vehicleId,
            @Size(max = 500) String message) {}

    public record BoardOfferResponse(UUID id, UUID requestId, String driverSub, String driverName, String vehicle,
                                     BigDecimal price, BigDecimal driverReceives, String message, boolean newRide,
                                     UUID rideId, LocalDateTime departureDate, BoardOfferStatus status, Instant createdAt) {}

    /** Carona já publicada do motorista que passa pelo caminho do pedido. */
    public record MatchingRide(UUID id, String originCity, String destinationCity, LocalDateTime departureDate) {}

    /**
     * Para quem não é o dono, o local vem aproximado (~1 km) e sem endereço, e os dados de quem
     * recebe não aparecem: o motorista só os vê na reserva/encomenda, depois de escolhido.
     */
    public record BoardResponse(
            UUID id, BoardKind kind, BoardStatus status, boolean mine, String requesterName, UUID companyId,
            String companyName, String originCity, String originState, Double originLatitude, Double originLongitude,
            String originAddress, String destinationCity, String destinationState, Double destinationLatitude,
            Double destinationLongitude, String destinationAddress, LocalDateTime desiredAt, int tripDistanceMeters,
            Integer distanceMeters, MatchingRide matchingRide, String notes,
            PackageSize size, String description, BigDecimal weightKg, Integer lengthCm, Integer widthCm, Integer heightCm,
            Boolean fragile, String recipientName, String recipientPhone,
            long pendingOffers, BoardOfferResponse myOffer,
            UUID rideId, UUID bookingId, UUID deliveryId, Instant createdAt, Instant closedAt) {}
}
