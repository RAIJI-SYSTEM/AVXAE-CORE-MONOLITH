package com.raiji.rides.web;

import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideIntent;
import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentType;
import com.raiji.rides.dto.RideIntentCreateRequest;
import com.raiji.rides.dto.RideIntentResponse;
import com.raiji.rides.service.RideIntentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ride-intents")
@PreAuthorize("hasAnyRole('PASSENGER', 'DRIVER')")
public class RideIntentController {

    private final RideIntentService service;

    public RideIntentController(RideIntentService service){
        this.service = service;
    }


    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RideIntentResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RideIntentCreateRequest request
    ) {
        RideIntent intent = service.create(
                jwt.getSubject(),
                request.type(),
                request.mode(),
                request.originCity(),
                request.originState(),
                request.originLatitude(),
                request.originLongitude(),
                request.destinationCity(),
                request.destinationState(),
                request.destinationLatitude(),
                request.destinationLongitude(),
                request.desiredAt()
        );

        return RideIntentResponse.from(intent);
    }

    @GetMapping("/nearby")
    public List<RideIntentResponse> nearby(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam RideIntentType type,
            @RequestParam RideIntentMode mode,
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "5000") double radiusMeters) {

        return service.findNearby(
                        jwt.getSubject(),
                        type,
                        mode,
                        latitude,
                        longitude,
                        radiusMeters)
                .stream()
                .map(RideIntentResponse::from)
                .toList();
    }

    @GetMapping("/me")
    public List<RideIntentResponse> myIntents(
            @AuthenticationPrincipal Jwt jwt) {

        return service.findMyIntents(jwt.getSubject())
                .stream()
                .map(RideIntentResponse::from)
                .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {

        service.cancel(jwt.getSubject(), id);
    }
}