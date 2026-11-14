package com.raiji.rides.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RideType;
import com.raiji.rides.geo.LatLng;
import com.raiji.rides.geo.Place;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Publicar uma carona — a MESMA para "agora" (partida em até 1h) e agendada.
 *
 * <ul>
 *   <li>pricePerPassenger == null => gratuita; > 0 => paga (comissão da plataforma descontada do motorista)</li>
 *   <li>type: PASSENGER (só pessoas), CARGO (só encomendas, 0 vagas) ou MIXED (os dois)</li>
 *   <li>cargoDetails obrigatório em CARGO/MIXED</li>
 *   <li>origin/destination (coordenadas do mapa) habilitam a busca por proximidade e pelo caminho;
 *       route = pontos do caminho (Mapbox Directions); sem ela, linha reta origem → destino</li>
 *   <li>departureDate é hora de Brasília: agora + 5min ≤ departureDate ≤ agora + 3 meses (RN10)</li>
 * </ul>
 */
public record RideRequest(
        @NotNull
        UUID vehicleId,

        @NotNull
        RideType type,

        @NotBlank
        @Size(max = 100)
        String originCity,

        @NotBlank
        @Pattern(regexp = "[A-Za-z]{2}", message = "originState deve ter 2 letras")
        String originState,

        @NotBlank
        @Size(max = 100)
        String destinationCity,

        @NotBlank
        @Pattern(regexp = "[A-Za-z]{2}", message = "destinationState deve ter 2 letras")
        String destinationState,

        @NotNull
        LocalDateTime departureDate,

        BigDecimal pricePerPassenger,

        @NotNull
        @Min(0)
        Integer totalSeats,

        @Valid
        CargoDetails cargoDetails,

        @Valid
        Place origin,

        @Valid
        Place destination,

        @Valid
        @Size(max = 5000, message = "A rota tem pontos demais (máximo 5000).")
        List<LatLng> route,

        @Size(max = 255)
        String meetingPoint,

        /** Preço por encomenda (só CARGO/MIXED); null = grátis. */
        BigDecimal pricePerPackage
) {
    /** Construtor sem localização (contrato antigo: só cidades). */
    public RideRequest(UUID vehicleId, RideType type, String originCity, String originState,
                       String destinationCity, String destinationState, LocalDateTime departureDate,
                       BigDecimal pricePerPassenger, Integer totalSeats, CargoDetails cargoDetails) {
        this(vehicleId, type, originCity, originState, destinationCity, destinationState, departureDate,
                pricePerPassenger, totalSeats, cargoDetails, null, null, null, null, null);
    }
}
