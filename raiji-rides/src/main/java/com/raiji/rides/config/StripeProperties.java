package com.raiji.rides.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S2-05 / RN04 — configuração do Stripe (modo sandbox/testes).
 *
 * <p>Prefixed em {@code raiji.stripe}. A chave de API vem de variável de
 * ambiente ({@code STRIPE_API_KEY}) e NUNCA é commitada. Quando ausente ou
 * em branco, {@link #configured()} retorna {@code false} e o
 * {@code StripeService} não faz nenhuma chamada real (devolve falha
 * controlada) — importante para ambientes sem credencial.
 *
 * @param apiKey        chave secreta (ex.: {@code sk_test_...})
 * @param webhookSecret segredo de verificação de webhooks (futuro)
 */
@ConfigurationProperties(prefix = "raiji.stripe")
public record StripeProperties(
        String apiKey,
        String webhookSecret
) {

    /** {@code true} somente com uma chave secreta plausível (sk_/rk_). */
    public boolean configured() {
        return apiKey != null
                && !apiKey.isBlank()
                && (apiKey.startsWith("sk_") || apiKey.startsWith("rk_"));
    }
}
