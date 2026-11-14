package com.raiji.deliveries.web;

import java.util.List;
import java.util.UUID;

import com.raiji.deliveries.dto.CodeRequest;
import com.raiji.deliveries.dto.DeliveryCreateRequest;
import com.raiji.deliveries.dto.DeliveryDecisionRequest;
import com.raiji.deliveries.dto.DeliveryResponse;
import com.raiji.deliveries.dto.ReasonRequest;
import com.raiji.deliveries.service.DeliveryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST  /api/rides/{rideId}/deliveries              pedir espaço para uma encomenda
 * GET   /api/rides/{rideId}/deliveries              (motorista) encomendas da carona
 * PATCH /api/rides/{rideId}/deliveries/{id}         (motorista) ACCEPT / REJECT
 * POST  /api/deliveries/{id}/pickup  { code }       (motorista) coleta com o código do remetente
 * POST  /api/deliveries/{id}/deliver { code }       (motorista) entrega com a palavra-chave
 * POST  /api/deliveries/{id}/cancel  { reason }     remetente ou motorista, antes da coleta
 * GET   /api/deliveries/mine                        minhas encomendas (e das minhas empresas)
 * GET   /api/deliveries/driving                     (motorista) encomendas que eu levo
 * GET   /api/deliveries/{id}
 */
@RestController
@PreAuthorize("isAuthenticated()")
public class DeliveryController {

    private final DeliveryService service;

    public DeliveryController(DeliveryService service) {
        this.service = service;
    }

    @PostMapping("/api/rides/{rideId}/deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryResponse request(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID rideId,
                                    @Valid @RequestBody DeliveryCreateRequest body) {
        return service.request(rideId, jwt.getSubject(), jwt.getClaimAsString("name"), body);
    }

    @GetMapping("/api/rides/{rideId}/deliveries")
    @PreAuthorize("hasRole('DRIVER')")
    public List<DeliveryResponse> listForDriver(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID rideId) {
        return service.listForDriver(rideId, jwt.getSubject());
    }

    @PatchMapping("/api/rides/{rideId}/deliveries/{id}")
    @PreAuthorize("hasRole('DRIVER')")
    public DeliveryResponse decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID rideId, @PathVariable UUID id,
                                   @Valid @RequestBody DeliveryDecisionRequest body) {
        return service.decide(rideId, id, jwt.getSubject(),
                body.decision() == DeliveryDecisionRequest.Decision.ACCEPT, body.reason());
    }

    @PostMapping("/api/deliveries/{id}/pickup")
    @PreAuthorize("hasRole('DRIVER')")
    public DeliveryResponse pickUp(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @Valid @RequestBody CodeRequest body) {
        return service.pickUp(id, jwt.getSubject(), body.code());
    }

    @PostMapping("/api/deliveries/{id}/deliver")
    @PreAuthorize("hasRole('DRIVER')")
    public DeliveryResponse deliver(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @Valid @RequestBody CodeRequest body) {
        return service.deliver(id, jwt.getSubject(), body.code());
    }

    @PostMapping("/api/deliveries/{id}/cancel")
    public DeliveryResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @Valid @RequestBody(required = false) ReasonRequest body) {
        return service.cancel(id, jwt.getSubject(), body == null ? null : body.reason());
    }

    @GetMapping("/api/deliveries/mine")
    public List<DeliveryResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(jwt.getSubject());
    }

    @GetMapping("/api/deliveries/driving")
    @PreAuthorize("hasRole('DRIVER')")
    public List<DeliveryResponse> driving(@AuthenticationPrincipal Jwt jwt) {
        return service.driving(jwt.getSubject());
    }

    @GetMapping("/api/deliveries/{id}")
    public DeliveryResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(id, jwt.getSubject());
    }
}
