package com.raiji.rides.web;

import java.util.List;
import java.util.UUID;

import com.raiji.rides.dto.RidePassengerResponse;
import com.raiji.rides.dto.RideRequestCreateRequest;
import com.raiji.rides.service.RidePassengerService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rides/{rideId}/requests")
public class RideRequestController {

    private final RidePassengerService ridePassengerService;

    public RideRequestController(
            RidePassengerService ridePassengerService
    ) {
        this.ridePassengerService = ridePassengerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PASSENGER')")
    public RidePassengerResponse request(
            @PathVariable UUID rideId,
            @Valid @RequestBody RideRequestCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ridePassengerService.request(
                rideId,
                jwt.getSubject(),
                request.passengerName()
        );
    }

    @GetMapping
    @PreAuthorize("hasRole('DRIVER')")
    public List<RidePassengerResponse> listRequests(
            @PathVariable UUID rideId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ridePassengerService.listForDriver(
                rideId,
                jwt.getSubject()
        );
    }
}