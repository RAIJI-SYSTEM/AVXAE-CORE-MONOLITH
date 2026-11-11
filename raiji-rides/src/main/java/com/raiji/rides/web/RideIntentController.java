package com.raiji.rides.web;

import com.raiji.common.Roles;
import com.raiji.rides.domain.RideIntent;
import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentType;
import com.raiji.rides.dto.RideIntentCreateRequest;
import com.raiji.rides.dto.RideIntentResponse;
import com.raiji.rides.service.RideIntentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
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
            Authentication authentication,
            @Valid @RequestBody RideIntentCreateRequest request
    ) {
        // Toda conta é passageira; "estou disponível como motorista" exige a role DRIVER,
        // que só vem de uma CNH verificada.
        if (request.type() == RideIntentType.DRIVER_OFFER && !hasRole(authentication, Roles.DRIVER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Para se oferecer como motorista, verifique sua CNH (POST /api/users/me/driver/cnh).");
        }

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

        // Intenções de OUTRAS pessoas: local aproximado + distância (privacidade —
        // não expor o ponto exato de quem pediu carona a qualquer um num raio de até 100 km)
        return service.findNearby(
                        jwt.getSubject(),
                        type,
                        mode,
                        latitude,
                        longitude,
                        radiusMeters)
                .stream()
                .map(intent -> RideIntentResponse.approximate(intent, latitude, longitude))
                .toList();
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
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