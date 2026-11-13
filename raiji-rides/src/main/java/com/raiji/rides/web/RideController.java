package com.raiji.rides.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.dto.RideDetailResponse;
import com.raiji.rides.dto.RideSearchResult;
import com.raiji.rides.geo.LatLng;
import com.raiji.rides.service.RideLifecycleService;
import com.raiji.users.company.CompanyMembership;

import com.raiji.rides.dto.RideRequest;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.dto.RideSearchFilter;
import com.raiji.rides.dto.RideSearchResponse;
import com.raiji.rides.pricing.PriceBreakdown;
import com.raiji.rides.service.RideService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final RideLifecycleService lifecycle;
    private final CompanyMembership companyMembership;

    public RideController(RideService rideService, RideLifecycleService lifecycle,
                          CompanyMembership companyMembership) {
        this.rideService = rideService;
        this.lifecycle = lifecycle;
        this.companyMembership = companyMembership;
    }

    /**
     * Busca pelo caminho (mapa):
     * GET /api/rides/search?originLat&originLng[&destinationLat&destinationLng][&radiusMeters=3000]
     *     [&from&to][&kind=SEAT|PACKAGE]
     * Só origem = "perto de mim"; com destino = de A para B, inclusive embarcando e
     * descendo no meio da rota. Cada resultado traz o preço sugerido do trecho.
     */
    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public List<RideSearchResult> searchAlongRoute(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam double originLat,
            @RequestParam double originLng,
            @RequestParam(required = false) Double destinationLat,
            @RequestParam(required = false) Double destinationLng,
            @RequestParam(defaultValue = "3000") double radiusMeters,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "SEAT") RideService.SearchKind kind) {
        LatLng destino = destinationLat == null || destinationLng == null ? null
                : new LatLng(destinationLat, destinationLng);
        return rideService.searchAlongRoute(jwt.getSubject(), new LatLng(originLat, originLng), destino,
                radiusMeters, from, to, kind);
    }

    /** Minhas caronas como motorista (padrão: PLANNED e IN_PROGRESS). */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('DRIVER')")
    public List<RideResponse> mine(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(required = false) List<RideStatus> status) {
        return rideService.mine(jwt.getSubject(), status);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public RideDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return rideService.get(id, jwt.getSubject());
    }

    /** Não aceita mais pedidos (os pendentes são recusados). */
    @PostMapping("/{id}/close-bookings")
    @PreAuthorize("hasRole('DRIVER')")
    public RideResponse closeBookings(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lifecycle.closeBookings(id, jwt.getSubject());
    }

    /** Inicia a viagem (até 1h antes da partida); pendentes são recusados. */
    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('DRIVER')")
    public RideResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lifecycle.start(id, jwt.getSubject());
    }

    @PostMapping("/{id}/finish")
    @PreAuthorize("hasRole('DRIVER')")
    public RideResponse finish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lifecycle.finish(id, jwt.getSubject());
    }

    public record CancelRideRequest(String reason) {}

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('DRIVER')")
    public RideResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                               @RequestBody(required = false) CancelRideRequest body) {
        return lifecycle.cancel(id, jwt.getSubject(), body == null ? null : body.reason());
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
            Authentication authentication,
            @Valid @RequestBody RideRequest request) {

        // Conta PJ: o tipo de conta PJ ou participar de uma empresa (CNPJ conferido na Receita)
        boolean business = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PJ".equals(a.getAuthority()))
                || companyMembership.isMemberOfAnyCompany(jwt.getSubject());

        RideResponse response = rideService.create(
                request,
                jwt.getSubject(),
                jwt.getClaimAsString("name"),
                business);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Prévia para a tela de publicar: GET /api/rides/price-preview?price=50.00
     * responde quanto fica com a plataforma e quanto o motorista recebe por vaga.
     */
    @GetMapping("/price-preview")
    @PreAuthorize("isAuthenticated()")
    public PriceBreakdown pricePreview(@RequestParam BigDecimal price) {
        return rideService.previewPrice(price);
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
