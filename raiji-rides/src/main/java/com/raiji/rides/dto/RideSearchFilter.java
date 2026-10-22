package com.raiji.rides.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Filtros de busca de caronas (S1-05 / RF04).
 *
 * page/size/sort ficam a cargo do Pageable do Spring Data.
 */
public record RideSearchFilter(
        String originCity,
        String destinationCity,
        LocalDateTime departureFrom,
        LocalDateTime departureTo,
        BigDecimal maxPrice,
        Boolean freeOnly,
        Integer minSeats
) {
}
