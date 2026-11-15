package com.raiji.deliveries.domain;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.DeliveryStatusHistory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryStatusHistoryTest {

    @Test
    void deveCriarHistoricoComTodosOsCampos() {
        UUID deliveryId = UUID.randomUUID();
        Instant antes = Instant.now();

        DeliveryStatusHistory history = new DeliveryStatusHistory(
                deliveryId,
                DeliveryStatus.PENDING,
                DeliveryStatus.CONFIRMED,
                "usuario-123",
                "Entrega confirmada"
        );

        Instant depois = Instant.now();

        assertNull(history.getId());
        assertEquals(deliveryId, history.getDeliveryId());
        assertEquals(DeliveryStatus.PENDING, history.getFromStatus());
        assertEquals(DeliveryStatus.CONFIRMED, history.getToStatus());
        assertEquals("usuario-123", history.getChangedBy());
        assertEquals("Entrega confirmada", history.getReason());

        assertNotNull(history.getCreatedAt());
        assertFalse(history.getCreatedAt().isBefore(antes));
        assertFalse(history.getCreatedAt().isAfter(depois));
    }

    @Test
    void deveCriarHistoricoInicialSemStatusAnterior() {
        UUID deliveryId = UUID.randomUUID();

        DeliveryStatusHistory history = new DeliveryStatusHistory(
                deliveryId,
                null,
                DeliveryStatus.PENDING,
                "sistema",
                "Entrega criada"
        );

        assertNull(history.getId());
        assertEquals(deliveryId, history.getDeliveryId());
        assertNull(history.getFromStatus());
        assertEquals(DeliveryStatus.PENDING, history.getToStatus());
        assertEquals("sistema", history.getChangedBy());
        assertEquals("Entrega criada", history.getReason());
        assertNotNull(history.getCreatedAt());
    }

    @Test
    void devePermitirCamposOpcionaisNulos() {
        UUID deliveryId = UUID.randomUUID();

        DeliveryStatusHistory history = new DeliveryStatusHistory(
                deliveryId,
                DeliveryStatus.PENDING,
                DeliveryStatus.CANCELLED,
                null,
                null
        );

        assertEquals(deliveryId, history.getDeliveryId());
        assertEquals(DeliveryStatus.PENDING, history.getFromStatus());
        assertEquals(DeliveryStatus.CANCELLED, history.getToStatus());
        assertNull(history.getChangedBy());
        assertNull(history.getReason());
        assertNotNull(history.getCreatedAt());
    }
}
