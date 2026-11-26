package com.raiji.deliveries.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.rides.cargo.CargoLoad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.raiji.deliveries.support.Fixtures;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryRideBookingsTest {

    @Mock DeliveryRepository deliveries;
    @Mock DeliveryNotifier notifier;
    @Mock DeliveryRefunds refunds;
    @InjectMocks DeliveryRideBookings bookings;

    private final UUID rideId = UUID.randomUUID();

    @Test
    void somaACargaConfirmada() {
        when(deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(rideId,
                List.of(DeliveryStatus.CONFIRMED, DeliveryStatus.IN_TRANSIT)))
                .thenReturn(List.of(Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED), Fixtures.delivery(rideId, DeliveryStatus.IN_TRANSIT)));

        CargoLoad carga = bookings.confirmedLoad(rideId);

        assertThat(carga).isEqualTo(CargoLoad.of(50, 40, 30, 2, new java.math.BigDecimal("2.00")));
    }

    @Test
    void semEncomendasACargaEZerada() {
        when(deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(rideId,
                List.of(DeliveryStatus.CONFIRMED, DeliveryStatus.IN_TRANSIT))).thenReturn(List.of());
        assertThat(bookings.confirmedLoad(rideId)).isEqualTo(CargoLoad.NONE);
    }

    @Test
    void recusaPendentesEEstornaOQueJaFoiPago() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        when(deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(rideId, List.of(DeliveryStatus.PENDING)))
                .thenReturn(List.of(d));

        bookings.rejectPending(rideId, "sem espaço");

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(d.getCancellationReason()).isEqualTo("sem espaço");
        verify(refunds).refundIfPaid(d, null);
        verify(deliveries).save(d);
        verify(notifier).sender(d, "DELIVERY_REJECTED", "Sua encomenda não vai nesta carona. Motivo: sem espaço");
    }

    @Test
    void cancelaAsConfirmadasQueNaoForamColetadas() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED);
        when(deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(rideId, List.of(DeliveryStatus.CONFIRMED)))
                .thenReturn(List.of(d));

        bookings.cancelNotPickedUp(rideId, "viagem cancelada");

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        verify(notifier).sender(d, "DELIVERY_CANCELLED", "Sua encomenda não vai nesta carona. Motivo: viagem cancelada");
    }

    @Test
    void indicaSeHaAlgumaEncomendaAtiva() {
        when(deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(rideId,
                List.of(DeliveryStatus.PENDING, DeliveryStatus.CONFIRMED, DeliveryStatus.IN_TRANSIT)))
                .thenReturn(List.of(Fixtures.delivery(rideId, DeliveryStatus.PENDING)));
        assertThat(bookings.hasActive(rideId)).isTrue();

        when(deliveries.findByRideIdAndStatusInAndDeletedAtIsNull(rideId,
                List.of(DeliveryStatus.PENDING, DeliveryStatus.CONFIRMED, DeliveryStatus.IN_TRANSIT)))
                .thenReturn(List.of());
        assertThat(bookings.hasActive(rideId)).isFalse();
    }
}
