package com.raiji.deliveries.dto;

import java.util.UUID;

import com.raiji.rides.geo.Place;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/rides/{rideId}/deliveries — pedir espaço para uma encomenda.
 *
 * @param pickup    onde o motorista pega (null = na origem da viagem)
 * @param dropoff   onde entrega (null = no destino da viagem)
 * @param companyId enviar em nome de uma empresa (precisa ser membro)
 */
public record DeliveryCreateRequest(
        @NotNull @Valid PackageRequest pkg,
        @Valid Place pickup,
        @Valid Place dropoff,
        UUID companyId,
        @Size(max = 150) String senderName
) {
}
