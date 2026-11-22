package com.raiji.rides.payout;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repasses.
 * <ul>
 *   <li>Motorista: {@code GET /api/drivers/me/payouts} (saldo a receber, itens e histórico),
 *       {@code PUT /api/drivers/me/pix-key};</li>
 *   <li>Admin (role {@code admin} do Keycloak): {@code GET /api/admin/payouts/balances},
 *       {@code GET /api/admin/payouts}, {@code POST /api/admin/payouts} (registra o Pix feito).</li>
 * </ul>
 */
@RestController
public class DriverPayoutController {

    public record PixKeyRequest(String pixKey) {}

    public record RegisterPayoutRequest(String driverSub, Long amountCents, String note) {}

    private final DriverPayoutService service;

    public DriverPayoutController(DriverPayoutService service) {
        this.service = service;
    }

    @GetMapping("/api/drivers/me/payouts")
    @PreAuthorize("hasRole('DRIVER')")
    public DriverPayoutService.DriverSummary mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(jwt.getSubject());
    }

    @PutMapping("/api/drivers/me/pix-key")
    @PreAuthorize("hasRole('DRIVER')")
    public Map<String, String> savePixKey(@AuthenticationPrincipal Jwt jwt, @RequestBody PixKeyRequest request) {
        return Map.of("pixKey", service.savePixKey(jwt.getSubject(), request.pixKey()));
    }

    @GetMapping("/api/admin/payouts/balances")
    @PreAuthorize("hasRole('admin')")
    public List<DriverPayoutService.DriverBalance> balances() {
        return service.balances();
    }

    @GetMapping("/api/admin/payouts")
    @PreAuthorize("hasRole('admin')")
    public List<DriverPayout> history() {
        return service.history();
    }

    @PostMapping("/api/admin/payouts")
    @PreAuthorize("hasRole('admin')")
    public DriverPayout register(@AuthenticationPrincipal Jwt jwt, @RequestBody RegisterPayoutRequest request) {
        if (request.driverSub() == null || request.amountCents() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Informe o motorista e o valor.");
        }
        return service.registerPayout(request.driverSub(), request.amountCents(), request.note(), jwt.getSubject());
    }
}
