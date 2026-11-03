package com.raiji.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raiji.rides.config.StripeProperties;
import com.raiji.rides.service.stripe.StripeRefundGatewayImpl;
import com.raiji.rides.service.stripe.StripeRefundResult;
import com.raiji.rides.service.stripe.StripeService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * S2-05 / RN04 — integração REAL com o sandbox do Stripe, habilitada SOMENTE
 * quando {@code STRIPE_TEST_API_KEY} (uma chave {@code sk_test_*}) está presente
 * no ambiente. Sem a chave, o teste é ignorado — assim o build/CI continua verde
 * e NUNCA expõe credenciais (a chave vem de variável de ambiente, jamais do repo).
 *
 * <p>Não faz cobrança real: tenta estornar um PaymentIntent inexistente e
 * verifica que o {@link StripeService} trata a falha graciosamente (resultado
 * {@code ok=false}, sem propagar exceção), exercitando o SDK de verdade.
 */
@EnabledIfEnvironmentVariable(named = "STRIPE_TEST_API_KEY", matches = ".+")
@DisplayName("Stripe sandbox (sk_test_*) — integração real opcional (S2-05/RN04)")
class StripeSandboxRefundIT {

    @Test
    @DisplayName("refund de PaymentIntent inexistente falha graciosamente (sem exceção)")
    void refundInexistenteFalhaGraciosamente() {
        String key = System.getenv("STRIPE_TEST_API_KEY");
        assertTrue(key.startsWith("sk_test_"),
                "use uma chave de TESTE (sk_test_*), nunca de produção");

        StripeProperties props = new StripeProperties(key, null);
        StripeService service =
                new StripeService(props, new StripeRefundGatewayImpl(props));

        StripeRefundResult result =
                service.refund("pi_inexistente_" + System.nanoTime(), 100L);

        assertFalse(result.ok(), "PaymentIntent inexistente deve falhar");
        assertNotNull(result.error(), "o erro deve ser capturado e retornado");
    }
}
