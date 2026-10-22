package com.raiji.rides.dto;

import java.util.List;

import com.raiji.rides.domain.Ride;
import org.springframework.data.domain.Page;

/**
 * Resposta paginada da busca de caronas no formato pedido na S1-05:
 * content, page, size, totalElements, totalPages.
 */
public record RideSearchResponse(
        List<RideResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static RideSearchResponse from(Page<Ride> page) {
        List<RideResponse> content = page.getContent().stream()
                .map(RideResponse::from)
                .toList();

        return new RideSearchResponse(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
