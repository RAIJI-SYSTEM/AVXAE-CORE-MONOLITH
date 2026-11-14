package com.raiji.deliveries.freight;

import java.util.List;
import java.util.UUID;

import com.raiji.deliveries.freight.FreightDtos.AcceptDirectRequest;
import com.raiji.deliveries.freight.FreightDtos.CreateFreightRequest;
import com.raiji.deliveries.freight.FreightDtos.CreateOfferRequest;
import com.raiji.deliveries.freight.FreightDtos.DeclineRequest;
import com.raiji.deliveries.freight.FreightDtos.FreightResponse;
import com.raiji.deliveries.freight.FreightDtos.OfferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Empresa (qualquer membro):
 *   POST   /api/companies/{companyId}/freight-requests          direta (targetDriverSub + offeredPrice) ou pública
 *   GET    /api/companies/{companyId}/freight-requests
 *   GET    /api/freight-requests/{id}/offers
 *   POST   /api/freight-requests/{id}/offers/{offerId}/accept   escolhe a oferta (fecha o frete)
 *   POST   /api/freight-requests/{id}/cancel
 * Motorista:
 *   GET    /api/freight-requests/for-me                         propostas diretas para mim
 *   GET    /api/freight-requests/nearby?lat&lng&radiusMeters    pedidos públicos perto
 *   POST   /api/freight-requests/{id}/accept   { vehicleId }    aceita a proposta direta
 *   POST   /api/freight-requests/{id}/decline  { reason }       recusa a proposta direta
 *   POST   /api/freight-requests/{id}/offers   { price, vehicleId, message }
 *   DELETE /api/freight-requests/{id}/offers/{offerId}          retira a oferta
 *   GET    /api/freight-offers/mine
 * Ambos: GET /api/freight-requests/{id}
 */
@RestController
@PreAuthorize("isAuthenticated()")
public class FreightController {

    private final FreightService service;

    public FreightController(FreightService service) {
        this.service = service;
    }

    @PostMapping("/api/companies/{companyId}/freight-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public FreightResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID companyId,
                                  @Valid @RequestBody CreateFreightRequest body) {
        return service.create(companyId, jwt.getSubject(), body);
    }

    @GetMapping("/api/companies/{companyId}/freight-requests")
    public List<FreightResponse> listForCompany(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID companyId) {
        return service.listForCompany(companyId, jwt.getSubject());
    }

    @GetMapping("/api/freight-requests/for-me")
    @PreAuthorize("hasRole('DRIVER')")
    public List<FreightResponse> forMe(@AuthenticationPrincipal Jwt jwt) {
        return service.forMe(jwt.getSubject());
    }

    @GetMapping("/api/freight-requests/nearby")
    @PreAuthorize("hasRole('DRIVER')")
    public List<FreightResponse> nearby(@AuthenticationPrincipal Jwt jwt, @RequestParam double lat,
                                        @RequestParam double lng,
                                        @RequestParam(defaultValue = "20000") double radiusMeters) {
        return service.nearby(jwt.getSubject(), lat, lng, radiusMeters);
    }

    @GetMapping("/api/freight-requests/{id}")
    public FreightResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(id, jwt.getSubject());
    }

    @PostMapping("/api/freight-requests/{id}/cancel")
    public FreightResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.cancel(id, jwt.getSubject());
    }

    @GetMapping("/api/freight-requests/{id}/offers")
    public List<OfferResponse> offers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.offersFor(id, jwt.getSubject());
    }

    @PostMapping("/api/freight-requests/{id}/offers/{offerId}/accept")
    public FreightResponse acceptOffer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @PathVariable UUID offerId) {
        return service.acceptOffer(id, offerId, jwt.getSubject());
    }

    @PostMapping("/api/freight-requests/{id}/accept")
    @PreAuthorize("hasRole('DRIVER')")
    public FreightResponse acceptDirect(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                        @Valid @RequestBody AcceptDirectRequest body) {
        return service.acceptDirect(id, jwt.getSubject(), jwt.getClaimAsString("name"), body.vehicleId());
    }

    @PostMapping("/api/freight-requests/{id}/decline")
    @PreAuthorize("hasRole('DRIVER')")
    public FreightResponse declineDirect(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                         @Valid @RequestBody(required = false) DeclineRequest body) {
        return service.declineDirect(id, jwt.getSubject(), body == null ? null : body.reason());
    }

    @PostMapping("/api/freight-requests/{id}/offers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DRIVER')")
    public OfferResponse offer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                               @Valid @RequestBody CreateOfferRequest body) {
        return service.offer(id, jwt.getSubject(), jwt.getClaimAsString("name"), body);
    }

    @DeleteMapping("/api/freight-requests/{id}/offers/{offerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('DRIVER')")
    public void withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID offerId) {
        service.withdrawOffer(id, offerId, jwt.getSubject());
    }

    @GetMapping("/api/freight-offers/mine")
    @PreAuthorize("hasRole('DRIVER')")
    public List<OfferResponse> myOffers(@AuthenticationPrincipal Jwt jwt) {
        return service.myOffers(jwt.getSubject());
    }
}
