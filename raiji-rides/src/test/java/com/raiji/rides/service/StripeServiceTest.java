package com.raiji.rides.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.raiji.rides.config.StripeProperties;
import com.raiji.rides.service.stripe.StripeRefundGateway;
import com.raiji.rides.service.stripe.StripeRefundResult;
import com.raiji.rides.service.stripe.StripeService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S2-05 / RN04 — testes unitários do {@link StripeService} com um gateway
 * fake (sem rede e sem chave real). Cobre: sucesso, falha capturada (não
 * propaga), não-configurado (não chama a API) e paymentIntent ausente.
 */
@DisplayName("StripeService — estorno (RN04)")
class StripeServiceTest {

    private static StripeProperties props(String apiKey) {
        return new StripeProperties(apiKey, null);
    }

    @Test
    @DisplayName("gateway OK => resultado de sucesso com refundId")
    void deveEstornarComSucesso() {
        StripeRefundGateway fake = (pi, cents) -> "re_FAKE_1";
        StripeService service = new StripeService(props("sk_test_123"), fake);

        StripeRefundResult r = service.refund("pi_1", 5000);

        assertTrue(r.ok());
        assertEquals("re_FAKE_1", r.refundId());
        assertNull(r.error());
    }

    @Test
    @DisplayName("falha no gateway => resultado de falha, SEM propagar exceção")
    void deveCapturarFalhaSemPropagar() {
        StripeRefundGateway fake = (pi, cents) -> {
            throw new RuntimeException("card declined");
        };
        StripeService service = new StripeService(props("sk_test_123"), fake);

        StripeRefundResult r = service.refund("pi_1", 5000);

        assertFalse(r.ok());
        assertNull(r.refundId());
        assertTrue(r.error().contains("card declined"));
    }

    @Test
    @DisplayName("Stripe não configurado => falha controlada e NENHUMA chamada real")
    void naoDeveChamarGatewayQuandoNaoConfigurado() {
        AtomicInteger calls = new AtomicInteger();
        StripeRefundGateway fake = (pi, cents) -> {
            calls.incrementAndGet();
            return "re_X";
        };
        StripeService service = new StripeService(props(""), fake); // chave em branco

        StripeRefundResult r = service.refund("pi_1", 5000);

        assertFalse(r.ok());
        assertEquals(0, calls.get(), "não pode chamar a API sem credencial");
        assertTrue(r.error().toLowerCase().contains("configur"));
    }

    @Test
    @DisplayName("paymentIntentId nulo/em branco => falha, sem chamar o gateway")
    void deveFalharQuandoPaymentIntentAusente() {
        AtomicInteger calls = new AtomicInteger();
        StripeRefundGateway fake = (pi, cents) -> {
            calls.incrementAndGet();
            return "re_X";
        };
        StripeService service = new StripeService(props("sk_test_123"), fake);

        assertFalse(service.refund(null, 100).ok());
        assertFalse(service.refund("   ", 100).ok());
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("o valor em centavos é repassado ao gateway")
    void deveRepassarValorEmCentavos() {
        AtomicLong captured = new AtomicLong();
        StripeRefundGateway fake = (pi, cents) -> {
            captured.set(cents);
            return "re_1";
        };
        StripeService service = new StripeService(props("sk_test_123"), fake);

        service.refund("pi_1", 12345);

        assertEquals(12345L, captured.get());
    }
}
