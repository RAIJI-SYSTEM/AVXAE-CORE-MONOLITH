package com.raiji.rides.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Map;

import com.raiji.common.enums.PaymentStatus;
import com.raiji.rides.config.StripeProperties;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.service.stripe.StripePaymentGateway;
import com.stripe.exception.StripeException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * [Análise de Falhas] Pagamento quando o Stripe falha.
 *
 * <p>Regra de negócio: falha ao iniciar a cobrança vira 502 amigável; falha ao
 * consultar não altera o pedido e não propaga exceção. Esses são os caminhos
 * usados para diagnosticar indisponibilidade do provedor sem derrubar o fluxo.
 */
@DisplayName("[Análise de Falhas] PaymentService — falhas do Stripe")
class PaymentServiceFailureTest {

    private StripePaymentGateway gateway;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        gateway = mock(StripePaymentGateway.class);
        service = new PaymentService(
                new StripeProperties("sk_test_123", "whsec_1"),
                gateway,
                Clock.systemUTC());
    }

    @Test
    @DisplayName("falha ao criar cobrança => 502, sem propagar erro do provedor")
    void falhaAoIniciarPagamento() throws Exception {
        when(gateway.create(anyLong(), anyString(), any(), anyString()))
                .thenThrow(mock(StripeException.class));

        RidePassenger passageiro = new RidePassenger();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.start(passageiro, 5000, "vaga", Map.of(), "ride-booking:1"));

        assertEquals(502, ex.getStatusCode().value());
        assertEquals("Não foi possível iniciar o pagamento. Tente de novo.", ex.getReason());
        verify(gateway).create(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("falha ao consultar não muda o pedido e não propaga exceção")
    void falhaAoConsultarPagamento() throws Exception {
        RidePassenger passageiro = new RidePassenger();
        passageiro.setPaymentIntentId("pi_1");
        when(gateway.retrieve("pi_1")).thenThrow(mock(StripeException.class));

        boolean mudou = service.refresh(passageiro);

        assertFalse(mudou);
        assertEquals(PaymentStatus.UNPAID, passageiro.getPaymentStatus());
        verify(gateway).retrieve("pi_1");
    }

    @Test
    @DisplayName("pedido já pago não consulta o Stripe de novo")
    void pedidoFinalNaoConsultaProvedor() throws Exception {
        RidePassenger passageiro = new RidePassenger();
        passageiro.setPaymentIntentId("pi_1");
        passageiro.setPaymentStatus(PaymentStatus.PAID);

        assertFalse(service.refresh(passageiro));
        verify(gateway, never()).retrieve(anyString());
    }
}
