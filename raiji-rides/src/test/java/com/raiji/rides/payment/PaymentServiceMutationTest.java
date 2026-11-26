package com.raiji.rides.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.raiji.common.enums.PaymentStatus;
import com.raiji.rides.config.StripeProperties;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.rewards.CouponService;
import com.raiji.rides.service.stripe.PaymentIntentInfo;
import com.raiji.rides.service.stripe.StripePaymentGateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Mutação] Casos adicionais do PaymentService para eliminar mutantes de:
 * fallback do valor recebido, retorno do refresh, estados que não geram nova
 * cobrança e efeitos colaterais (cupom).
 */
@DisplayName("[Mutação] PaymentService — caminhos de borda")
class PaymentServiceMutationTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

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
    @DisplayName("refresh confirmado retorna true; amountReceived=0 usa o valor da cobrança")
    void refreshConfirmadoUsaValorDaCobranca() throws Exception {
        RidePassenger p = new RidePassenger();
        p.setPaymentIntentId("pi_1");
        when(gateway.retrieve("pi_1")).thenReturn(intent("pi_1", "succeeded", 5000, 0));

        assertTrue(service.refresh(p));
        assertEquals(PaymentStatus.PAID, p.getPaymentStatus());
        assertEquals(5000L, p.getPaidAmountCents());
        assertEquals(NOW, p.getPaidAt());
    }

    @Test
    @DisplayName("refresh sem paymentIntent, já final ou sem Stripe configurado => false")
    void refreshNaoConsultaQuandoNaoAplicavel() throws Exception {
        RidePassenger semIntent = new RidePassenger();
        assertFalse(service.refresh(semIntent));

        RidePassenger pago = new RidePassenger();
        pago.setPaymentIntentId("pi_1");
        pago.setPaymentStatus(PaymentStatus.PAID);
        assertFalse(service.refresh(pago));

        PaymentService semChave = new PaymentService(new StripeProperties("", ""), gateway, Clock.systemUTC());
        RidePassenger semConfig = new RidePassenger();
        semConfig.setPaymentIntentId("pi_1");
        assertFalse(semChave.refresh(semConfig));

        verify(gateway, never()).retrieve(anyString());
    }

    @Test
    @DisplayName("view usa o valor pago quando existe e o valor calculado quando não")
    void viewUsaValorPagoOuFallback() {
        RidePassenger p = new RidePassenger();

        assertEquals(5000L, service.view(p, 5000).amountCents());

        p.setPaidAmountCents(4000L);
        assertEquals(4000L, service.view(p, 5000).amountCents());
    }

    @Test
    @DisplayName("start em PROCESSING/REFUNDED não cria nova cobrança")
    void startNaoCobraEstadosFinaisOuEmProcessamento() throws Exception {
        RidePassenger processando = new RidePassenger();
        processando.setPaymentStatus(PaymentStatus.PROCESSING);
        assertEquals(PaymentStatus.PROCESSING, service.start(processando, 5000, "vaga", Map.of(), "ref").status());

        RidePassenger estornado = new RidePassenger();
        estornado.setPaymentStatus(PaymentStatus.REFUNDED);
        assertEquals(PaymentStatus.REFUNDED, service.start(estornado, 5000, "vaga", Map.of(), "ref").status());

        verify(gateway, never()).create(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("start volta o pedido para UNPAID após criar uma nova cobrança")
    void startMarcaUnpaidAoCriar() throws Exception {
        RidePassenger p = new RidePassenger();
        p.setPaymentStatus(PaymentStatus.NOT_REQUIRED);
        when(gateway.create(anyLong(), anyString(), any(), anyString()))
                .thenReturn(intent("pi_new", "requires_payment_method", 5000, 0));

        service.start(p, 5000, "vaga", Map.of(), "ride-booking:1");

        assertEquals(PaymentStatus.UNPAID, p.getPaymentStatus());
        assertEquals("pi_new", p.getPaymentIntentId());
    }

    @Test
    @DisplayName("apply confirmado avisa o cupom (onPaid)")
    void applyAvisaCupom() {
        CouponService coupons = mock(CouponService.class);
        service.setCoupons(coupons);
        RidePassenger p = new RidePassenger();
        p.setPaymentIntentId("pi_1");

        assertTrue(service.apply(p, intent("pi_1", "succeeded", 5000, 5000)));
        verify(coupons).onPaid(p);
    }
}
