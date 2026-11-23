package com.raiji.rides.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.raiji.common.enums.PaymentStatus;
import com.raiji.rides.config.StripeProperties;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.service.stripe.PaymentIntentInfo;
import com.raiji.rides.service.stripe.StripePaymentGateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("PaymentService — o backend só marca pago depois de conferir no Stripe")
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");

    private StripePaymentGateway gateway;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        gateway = mock(StripePaymentGateway.class);
        service = new PaymentService(new StripeProperties("sk_test_123", "whsec_1"), gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PaymentIntentInfo intent(String id, String status, long amount, long received) {
        return new PaymentIntentInfo(id, id + "_secret", status, amount, received, Map.of());
    }

    @Test
    @DisplayName("inicia: cria a cobrança com o valor do pedido e devolve o clientSecret")
    void deveCriarCobranca() throws Exception {
        RidePassenger p = new RidePassenger();
        when(gateway.create(eq(5000L), anyString(), any(), anyString()))
                .thenReturn(intent("pi_1", "requires_payment_method", 5000, 0));

        PaymentResponse r = service.start(p, 5000, "vaga", Map.of(), "ride-booking:1");

        assertEquals("pi_1_secret", r.clientSecret());
        assertEquals(PaymentStatus.UNPAID, r.status());
        assertEquals("pi_1", p.getPaymentIntentId());
        assertEquals(PaymentStatus.UNPAID, p.getPaymentStatus());
    }

    @Test
    @DisplayName("retoma: reaproveita a cobrança aberta com o mesmo valor (não cobra duas vezes)")
    void deveReaproveitarCobrancaAberta() throws Exception {
        RidePassenger p = new RidePassenger();
        p.setPaymentIntentId("pi_1");
        when(gateway.retrieve("pi_1")).thenReturn(intent("pi_1", "requires_payment_method", 5000, 0));

        PaymentResponse r = service.start(p, 5000, "vaga", Map.of(), "ride-booking:1");

        assertEquals("pi_1_secret", r.clientSecret());
        verify(gateway, never()).create(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Stripe confirmou => PAID com o valor recebido; repetir não muda nada")
    void deveMarcarPagoSoComConfirmacaoDoStripe() {
        RidePassenger p = new RidePassenger();
        p.setPaymentIntentId("pi_1");

        assertTrue(service.apply(p, intent("pi_1", "succeeded", 5000, 5000)));
        assertEquals(PaymentStatus.PAID, p.getPaymentStatus());
        assertEquals(5000L, p.getPaidAmountCents());
        assertEquals(NOW, p.getPaidAt());

        assertFalse(service.apply(p, intent("pi_1", "succeeded", 5000, 5000)), "já estava pago");
    }

    @Test
    @DisplayName("Pix gerado e aguardando => PROCESSING (ainda não pago)")
    void pixAguardandoFicaEmProcessamento() {
        RidePassenger p = new RidePassenger();
        p.setPaymentIntentId("pi_1");

        assertFalse(service.apply(p, intent("pi_1", "processing", 5000, 0)));
        assertEquals(PaymentStatus.PROCESSING, p.getPaymentStatus());
    }

    @Test
    @DisplayName("cobrança de OUTRO pedido é ignorada")
    void deveIgnorarCobrancaDeOutroPedido() {
        RidePassenger p = new RidePassenger();
        p.setPaymentIntentId("pi_1");

        assertFalse(service.apply(p, intent("pi_outro", "succeeded", 5000, 5000)));
        assertEquals(PaymentStatus.UNPAID, p.getPaymentStatus());
        assertNull(p.getPaidAt());
    }

    @Test
    @DisplayName("já pago => iniciar de novo só devolve a situação, sem nova cobrança")
    void naoDeveCobrarDeNovo() throws Exception {
        RidePassenger p = new RidePassenger();
        p.setPaymentIntentId("pi_1");
        p.setPaymentStatus(PaymentStatus.PAID);
        p.setPaidAmountCents(5000L);

        PaymentResponse r = service.start(p, 5000, "vaga", Map.of(), "ride-booking:1");

        assertEquals(PaymentStatus.PAID, r.status());
        assertNull(r.clientSecret());
        verify(gateway, never()).create(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("pedido gratuito: nada a pagar")
    void pedidoGratuito() {
        RidePassenger p = new RidePassenger();
        assertEquals(PaymentStatus.NOT_REQUIRED, PaymentService.statusOf(p, 0));
        assertThrows(ResponseStatusException.class, () -> service.start(p, 0, "vaga", Map.of(), "x"));
    }

    @Test
    @DisplayName("sem chave do Stripe => 503, sem chamar a API")
    void semStripeConfigurado() throws Exception {
        PaymentService semChave = new PaymentService(new StripeProperties("", ""), gateway, Clock.systemUTC());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> semChave.start(new RidePassenger(), 5000, "vaga", Map.of(), "x"));
        assertEquals(503, e.getStatusCode().value());
        verify(gateway, never()).retrieve(anyString());
    }

    @Test
    @DisplayName("R$ -> centavos")
    void centavos() {
        assertEquals(3250L, PaymentService.toCents(new BigDecimal("32.5")));
        assertEquals(0L, PaymentService.toCents(null));
    }
}
