package com.raiji.rides.web;

import java.util.List;

import com.raiji.rides.dto.MyBookingResponse;
import com.raiji.rides.service.RidePassengerService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Minhas viagens" como passageiro: GET /api/rides/bookings/mine */
@RestController
public class MyBookingsController {

    private final RidePassengerService ridePassengerService;

    public MyBookingsController(RidePassengerService ridePassengerService) {
        this.ridePassengerService = ridePassengerService;
    }

    @GetMapping("/api/rides/bookings/mine")
    @PreAuthorize("isAuthenticated()")
    public List<MyBookingResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return ridePassengerService.myBookings(jwt.getSubject());
    }
}
