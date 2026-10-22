package com.raiji.rides.web;

import java.math.BigDecimal;
import java.util.UUID;

import com.raiji.rides.dto.RideRequest;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.service.RideService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rides")
@PreAuthorize("hasRole('DRIVER')")
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    @PostMapping
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
    public ResponseEntity<RideResponse> updatePrice(
            @PathVariable UUID id,
            @RequestBody PriceUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        RideResponse response = rideService.updatePrice(
                id, request.pricePerPassenger(), jwt.getSubject());

        return ResponseEntity.ok(response);
    }

    public record PriceUpdateRequest(BigDecimal pricePerPassenger) {}
}
