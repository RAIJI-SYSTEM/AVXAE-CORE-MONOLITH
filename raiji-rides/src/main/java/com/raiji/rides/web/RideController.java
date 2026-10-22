package com.raiji.rides.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.raiji.rides.dto.RideRequest;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.dto.RideSearchFilter;
import com.raiji.rides.dto.RideSearchResponse;
import com.raiji.rides.service.RideService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RideSearchResponse> search(
            @PageableDefault(size = 20, sort = "departureDate", direction = Sort.Direction.ASC)
            Pageable pageable,
            @RequestParam(required = false) String originCity,
            @RequestParam(required = false) String destinationCity,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime departureFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime departureTo,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean freeOnly,
            @RequestParam(required = false) Integer minSeats) {

        RideSearchFilter filter = new RideSearchFilter(
                originCity,
                destinationCity,
                departureFrom,
                departureTo,
                maxPrice,
                freeOnly,
                minSeats);

        return ResponseEntity.ok(rideService.search(filter, pageable));
    }

    @PostMapping
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<RideResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RideRequest request) {

        RideResponse response = rideService.create(
                request,
                jwt.getSubject(),
                jwt.getClaimAsString("name"));

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/price")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<RideResponse> updatePrice(
            @PathVariable UUID id,
            @RequestBody PriceUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        RideResponse response = rideService.updatePrice(
                id,
                request.pricePerPassenger(),
                jwt.getSubject());

        return ResponseEntity.ok(response);
    }

    public record PriceUpdateRequest(BigDecimal pricePerPassenger) {}
}
