package com.raiji.deliveries.board;

import java.util.List;
import java.util.UUID;

import com.raiji.deliveries.board.BoardDtos.BoardOfferResponse;
import com.raiji.deliveries.board.BoardDtos.BoardResponse;
import com.raiji.deliveries.board.BoardDtos.CreateBoardOffer;
import com.raiji.deliveries.board.BoardDtos.CreateBoardRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mural da região.
 * Dono:      POST /api/board · GET /mine · GET /{id}/offers · POST /{id}/offers/{offerId}/accept · POST /{id}/cancel
 * Motorista: GET /region?lat&lng (opcionais) · GET /offered · POST /{id}/offers · DELETE /{id}/offers/{offerId}
 * Ambos:     GET /{id}
 */
@RestController
@RequestMapping("/api/board")
@PreAuthorize("isAuthenticated()")
public class BoardController {

    private final BoardService service;

    public BoardController(BoardService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BoardResponse publish(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateBoardRequest body) {
        return service.publish(jwt.getSubject(), jwt.getClaimAsString("name"), body);
    }

    @GetMapping("/mine")
    public List<BoardResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(jwt.getSubject());
    }

    @GetMapping("/region")
    @PreAuthorize("hasRole('DRIVER')")
    public List<BoardResponse> region(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Double lat,
                                      @RequestParam(required = false) Double lng) {
        return service.region(jwt.getSubject(), lat, lng);
    }

    @GetMapping("/offered")
    @PreAuthorize("hasRole('DRIVER')")
    public List<BoardResponse> offered(@AuthenticationPrincipal Jwt jwt) {
        return service.offered(jwt.getSubject());
    }

    @GetMapping("/{id}")
    public BoardResponse get(@AuthenticationPrincipal Jwt jwt, Authentication auth, @PathVariable UUID id) {
        boolean driver = auth.getAuthorities().stream().anyMatch(a -> "ROLE_DRIVER".equals(a.getAuthority()));
        return service.get(id, jwt.getSubject(), driver);
    }

    @PostMapping("/{id}/cancel")
    public BoardResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.cancel(id, jwt.getSubject());
    }

    @GetMapping("/{id}/offers")
    public List<BoardOfferResponse> offers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.offersFor(id, jwt.getSubject());
    }

    @PostMapping("/{id}/offers/{offerId}/accept")
    public BoardResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID offerId) {
        return service.accept(id, offerId, jwt.getSubject());
    }

    @PostMapping("/{id}/offers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DRIVER')")
    public BoardOfferResponse offer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @Valid @RequestBody CreateBoardOffer body) {
        return service.offer(id, jwt.getSubject(), jwt.getClaimAsString("name"), body);
    }

    @DeleteMapping("/{id}/offers/{offerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('DRIVER')")
    public void withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID offerId) {
        service.withdraw(id, offerId, jwt.getSubject());
    }
}
