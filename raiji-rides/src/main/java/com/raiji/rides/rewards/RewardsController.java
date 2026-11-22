package com.raiji.rides.rewards;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recompensas:
 * <ul>
 *   <li>GET /api/rewards: cupons e missão de anúncios da pessoa;</li>
 *   <li>POST /api/rewards/ad-watched: "assisti" vindo do app, só com {@code raiji.rewards.mock-ads};</li>
 *   <li>GET /api/public/rewards/admob-ssv: callback assinado do AdMob (o que vale em produção).</li>
 * </ul>
 */
@RestController
public class RewardsController {

    public record AdWatched(@Pattern(regexp = "short|long") String kind, @NotBlank @Size(max = 200) String transactionId) {
    }

    private static final Logger log = LoggerFactory.getLogger(RewardsController.class);

    private final RewardsService rewards;
    private final RewardsProperties properties;
    private final AdMobVerifier verifier;

    public RewardsController(RewardsService rewards, RewardsProperties properties, AdMobVerifier verifier) {
        this.rewards = rewards;
        this.properties = properties;
        this.verifier = verifier;
    }

    @GetMapping("/api/rewards")
    public RewardsState mine(@AuthenticationPrincipal Jwt jwt) {
        return rewards.state(jwt.getSubject());
    }

    @PostMapping("/api/rewards/ad-watched")
    public RewardsState adWatched(@Valid @RequestBody AdWatched body, @AuthenticationPrincipal Jwt jwt) {
        if (!properties.mockAds()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rewards.creditAd(jwt.getSubject(), body.kind(), "app:" + body.transactionId());
    }

    /**
     * Configure esta URL em cada bloco de anúncio premiado no AdMob (Configurações avançadas →
     * Verificação do lado do servidor). O app manda o id da pessoa em user_id e o tipo
     * ("short"/"long") em custom_data.
     */
    @GetMapping("/api/public/rewards/admob-ssv")
    public ResponseEntity<String> admobCallback(HttpServletRequest request) {
        var callback = verifier.verify(request.getQueryString());
        if (callback.isEmpty()) return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Assinatura inválida");
        var c = callback.get();
        // O botão "Verificar URL" do painel manda um callback sem usuário: só confirmamos
        if (c.userId() == null || c.userId().isBlank() || c.transactionId() == null) return ResponseEntity.ok("OK");
        String kind = "long".equals(c.customData()) ? "long" : "short";
        rewards.creditAd(c.userId(), kind, "admob:" + c.transactionId());
        log.info("Anúncio premiado creditado ({}, transação {})", kind, c.transactionId());
        return ResponseEntity.ok("OK");
    }
}
