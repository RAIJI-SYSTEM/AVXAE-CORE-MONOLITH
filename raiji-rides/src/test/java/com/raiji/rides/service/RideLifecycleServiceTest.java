package com.raiji.rides.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.cargo.RidePackageBookings;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.repository.RideRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RideLifecycleService - início da carona")
class RideLifecycleServiceTest {

    private RideRepository rideRepository;
    private RidePassengerService passengers;
    private RidePackageBookings packages;
    private RideLifecycleService lifecycle;

    private final Instant fixedInstant =
            Instant.parse("2026-11-22T12:00:00Z");

    @BeforeEach
    void setUp() {
        rideRepository = mock(RideRepository.class);
        passengers = mock(RidePassengerService.class);
        packages = mock(RidePackageBookings.class);

        Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        lifecycle = new RideLifecycleService(
                rideRepository,
                passengers,
                List.of(packages),
                clock
        );
    }

    @Test
    @DisplayName("iniciar coloca a carona em andamento e inicia encomendas confirmadas")
    void deveIniciarEncomendasQuandoCaronaComecar() {
        UUID rideId = UUID.randomUUID();

        Ride ride = new Ride();
        ride.setStatus(RideStatus.PLANNED);

        // A entidade pode exigir um ID atribuído pelo JPA.
        org.springframework.test.util.ReflectionTestUtils
                .setField(ride, "id", rideId);

        lifecycle.iniciar(ride);

        assertEquals(RideStatus.IN_PROGRESS, ride.getStatus());
        assertEquals(fixedInstant, ride.getStartedAt());

        verify(passengers).rejectAllPending(
                ride,
                RideLifecycleService.MOTIVO_INICIADA
        );

        verify(packages).rejectPending(
                rideId,
                RideLifecycleService.MOTIVO_INICIADA
        );

        verify(packages).startConfirmed(rideId, fixedInstant);
    }

    @Test
    @DisplayName("não inicia encomendas ao apenas encerrar reservas")
    void naoDeveIniciarEncomendasAoEncerrarReservas() {
        UUID rideId = UUID.randomUUID();

        Ride ride = new Ride();
        ride.setStatus(RideStatus.PLANNED);

        org.springframework.test.util.ReflectionTestUtils
                .setField(ride, "id", rideId);

        ride.setDriverSub("driver-123");

        when(rideRepository.findByIdForUpdate(rideId))
                .thenReturn(java.util.Optional.of(ride));

        when(rideRepository.save(ride))
                .thenReturn(ride);

        lifecycle.closeBookings(rideId, "driver-123");

        verify(packages, never()).startConfirmed(any(), any());
    }
}
