package com.raiji.deliveries.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.Delivery;
import com.raiji.deliveries.DeliveryStatusHistory;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.deliveries.repository.DeliveryStatusHistoryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("DeliveryRideBookings - integração com ciclo de vida da carona")
class DeliveryRideBookingsTest {

    private DeliveryRepository deliveries;
    private DeliveryStatusHistoryRepository history;
    private DeliveryNotifier notifier;
    private DeliveryRideBookings bookings;

    @BeforeEach
    void setUp() {
        deliveries = mock(DeliveryRepository.class);
        history = mock(DeliveryStatusHistoryRepository.class);
        notifier = mock(DeliveryNotifier.class);

        bookings = new DeliveryRideBookings(deliveries, history, notifier);
    }

    @Test
    @DisplayName("início da carona coloca encomendas confirmadas em trânsito")
    void deveColocarEncomendasConfirmadasEmTransito() {
        UUID rideId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-11-22T12:00:00Z");

        Delivery delivery = mock(Delivery.class);

        when(delivery.getId()).thenReturn(deliveryId);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.CONFIRMED);
        when(deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(
                rideId,
                List.of(DeliveryStatus.CONFIRMED)
        )).thenReturn(List.of(delivery));

        when(deliveries.save(delivery)).thenReturn(delivery);

        bookings.startConfirmed(rideId, startedAt);

        verify(delivery).startTransit(startedAt);
        verify(deliveries).save(delivery);

        ArgumentCaptor<DeliveryStatusHistory> captor =
                ArgumentCaptor.forClass(DeliveryStatusHistory.class);

        verify(history).save(captor.capture());

        DeliveryStatusHistory savedHistory = captor.getValue();

        assertEquals(deliveryId, savedHistory.getDeliveryId());
        assertEquals(DeliveryStatus.CONFIRMED, savedHistory.getFromStatus());
        assertEquals(DeliveryStatus.IN_TRANSIT, savedHistory.getToStatus());
        assertNull(savedHistory.getChangedBy());
        assertTrue(savedHistory.getReason().contains("Carona iniciada"));

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("não altera nada quando não existem encomendas confirmadas")
    void naoDeveAlterarQuandoNaoExistemEncomendasConfirmadas() {
        UUID rideId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-11-22T12:00:00Z");

        when(deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(
                rideId,
                List.of(DeliveryStatus.CONFIRMED)
        )).thenReturn(List.of());

        bookings.startConfirmed(rideId, startedAt);

        verify(deliveries, never()).save(any(Delivery.class));
        verifyNoInteractions(history);
        verifyNoInteractions(notifier);
    }
}
