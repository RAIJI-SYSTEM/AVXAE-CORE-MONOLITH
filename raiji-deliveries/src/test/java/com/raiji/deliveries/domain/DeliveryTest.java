package com.raiji.deliveries.domain;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.Delivery;
import com.raiji.deliveries.service.IllegalDeliveryStateException;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryTest {

    @Test
    void deveIniciarComoPendenteEAtiva() {
        Delivery delivery = new Delivery();

        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        assertTrue(delivery.active());
        assertNotNull(delivery.getRequestedAt());
    }

    @Test
    void deveConsiderarEntregaAtivaNosStatusPermitidos() {
        Delivery delivery = new Delivery();

        assertTrue(delivery.active());

        delivery.confirm();
        assertTrue(delivery.active());

        delivery.pickUp();
        assertTrue(delivery.active());
    }

    @Test
    void deveConsiderarEntregaInativaQuandoEntregue() {
        Delivery delivery = new Delivery();

        delivery.confirm();
        delivery.startTransit(Instant.now());
        delivery.deliver(Instant.now(), 0L);

        assertFalse(delivery.active());
    }

    @Test
    void deveConsiderarEntregaInativaQuandoCancelada() {
        Delivery delivery = new Delivery();

        delivery.cancel("Cancelamento solicitado");

        assertFalse(delivery.active());
    }

    @Test
    void deveCancelarEntregaPendente() {
        Delivery delivery = new Delivery();

        delivery.cancel("Motivo");

        assertEquals(DeliveryStatus.CANCELLED, delivery.getStatus());
        assertEquals("Motivo", delivery.getCancellationReason());
        assertNotNull(delivery.getCancelledAt());
    }

    @Test
    void deveCancelarEntregaConfirmada() {
        Delivery delivery = new Delivery();

        delivery.confirm();
        delivery.cancel("Motivo");

        assertEquals(DeliveryStatus.CANCELLED, delivery.getStatus());
    }

    @Test
    void deveCancelarEntregaEmTransito() {
        Delivery delivery = new Delivery();

        delivery.confirm();
        delivery.startTransit(Instant.now());
        delivery.cancel("Motivo");

        assertEquals(DeliveryStatus.CANCELLED, delivery.getStatus());
    }

    @Test
    void naoDeveCancelarEntregaEntregue() {
        Delivery delivery = new Delivery();

        delivery.confirm();
        delivery.startTransit(Instant.now());
        delivery.deliver(Instant.now(), 0L);

        assertThrows(
                IllegalDeliveryStateException.class,
                () -> delivery.cancel("Motivo")
        );
    }

    @Test
    void naoDeveCancelarEntregaJaCancelada() {
        Delivery delivery = new Delivery();

        delivery.cancel("Primeiro cancelamento");

        assertThrows(
                IllegalDeliveryStateException.class,
                () -> delivery.cancel("Segundo cancelamento")
        );
    }

    @Test
    void naoDeveConfirmarEntregaQueNaoEstejaPendente() {
        Delivery delivery = new Delivery();

        delivery.confirm();

        assertThrows(
                IllegalDeliveryStateException.class,
                delivery::confirm
        );
    }

    @Test
    void naoDeveRetirarEntregaQueNaoEstejaConfirmada() {
        Delivery delivery = new Delivery();

        assertThrows(
                IllegalDeliveryStateException.class,
                delivery::pickUp
        );
    }

    @Test
    void naoDeveEntregarEncomendaQueNaoEstejaEmTransito() {
        Delivery delivery = new Delivery();

        assertThrows(
                IllegalDeliveryStateException.class,
                () -> delivery.deliver(Instant.now(), 0L)
        );
    }

    @Test
    void deveAtualizarPaymentIntentId() {
        Delivery delivery = new Delivery();

        delivery.setPaymentIntentId("pi_teste_123");

        assertEquals("pi_teste_123", delivery.getPaymentIntentId());
    }

    @Test
    void deveAtualizarFreightRequestId() {
        Delivery delivery = new Delivery();
        UUID freightId = UUID.randomUUID();

        delivery.setFreightRequestId(freightId);

        assertEquals(freightId, delivery.getFreightRequestId());
    }
}
