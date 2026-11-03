package com.raiji.rides.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.service.stripe.StripeRefundResult;
import com.raiji.rides.service.stripe.StripeService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S2-05 / RN04 — testes unitários do {@link RideRefundService}.
 *
 * <p>Cobre os critérios de aceite: carona paga estorna + audita
 * (ESTORNO_SUCESSO com id do refund); falha do Stripe audita ESTORNO_FALHOU;
 * carona gratuita NÃO chama o Stripe; reserva sem paymentIntent não estorna.
 */
@DisplayName("RideRefundService — estorno automático (RN04)")
class RideRefundServiceTest {

    private StripeService stripeService;
    private AuditLogService auditLogService;
    private RideRefundService service;

    @BeforeEach
    void setUp() {
        stripeService = mock(StripeService.class);
        auditLogService = mock(AuditLogService.class);
        service = new RideRefundService(stripeService, auditLogService);
    }

    private Ride ride(String price) {
        Ride ride = new Ride();
        ReflectionTestUtils.setField(ride, "id", UUID.randomUUID());
        ride.setPricePerPassenger(price == null ? null : new BigDecimal(price));
        return ride;
    }

    private RidePassenger reserva(String paymentIntentId) {
        RidePassenger p = new RidePassenger();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setPaymentIntentId(paymentIntentId);
        return p;
    }

    @Test
    @DisplayName("carona paga + paymentIntent => estorna e audita ESTORNO_SUCESSO com refundId")
    void deveEstornarCaronaPaga() {
        Ride ride = ride("50.00");
        RidePassenger p = reserva("pi_1");
        when(stripeService.refund("pi_1", 5000L))
                .thenReturn(StripeRefundResult.success("re_1"));

        boolean tentou = service.refundIfApplicable(ride, p, "actor-sub");

        assertTrue(tentou);
        verify(stripeService).refund("pi_1", 5000L);

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                action.capture(),
                eq(RideRefundService.ENTITY_TYPE),
                eq(p.getId()),
                eq("actor-sub"),
                details.capture());

        assertEquals(RideRefundService.ACTION_SUCESSO, action.getValue());
        assertTrue(details.getValue().contains("re_1"),
                "a auditoria deve guardar o id do refund");
    }

    @Test
    @DisplayName("falha no Stripe => audita ESTORNO_FALHOU (não lança)")
    void deveRegistrarEstornoFalhou() {
        Ride ride = ride("10.00");
        RidePassenger p = reserva("pi_2");
        when(stripeService.refund("pi_2", 1000L))
                .thenReturn(StripeRefundResult.failure("timeout de rede"));

        service.refundIfApplicable(ride, p, "actor-sub");

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                action.capture(), any(), any(), any(), details.capture());

        assertEquals(RideRefundService.ACTION_FALHOU, action.getValue());
        assertTrue(details.getValue().contains("timeout de rede"));
    }

    @Test
    @DisplayName("carona GRATUITA => não chama o Stripe nem audita")
    void naoDeveEstornarCaronaGratuita() {
        Ride ride = ride(null);
        RidePassenger p = reserva("pi_3");

        boolean tentou = service.refundIfApplicable(ride, p, "actor-sub");

        assertFalse(tentou);
        verifyNoInteractions(stripeService);
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("carona paga SEM paymentIntent => nada a estornar")
    void naoDeveEstornarSemPaymentIntent() {
        Ride ride = ride("20.00");
        RidePassenger p = reserva(null);

        boolean tentou = service.refundIfApplicable(ride, p, "actor-sub");

        assertFalse(tentou);
        verifyNoInteractions(stripeService);
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("conversão R$ -> centavos (arredondamento HALF_UP)")
    void deveConverterReaisEmCentavos() {
        assertEquals(5000L, RideRefundService.toCents(new BigDecimal("50.00")));
        assertEquals(1050L, RideRefundService.toCents(new BigDecimal("10.50")));
        assertEquals(1L, RideRefundService.toCents(new BigDecimal("0.01")));
        assertEquals(0L, RideRefundService.toCents(new BigDecimal("0.00")));
        assertEquals(100L, RideRefundService.toCents(new BigDecimal("0.999")));
    }
}
