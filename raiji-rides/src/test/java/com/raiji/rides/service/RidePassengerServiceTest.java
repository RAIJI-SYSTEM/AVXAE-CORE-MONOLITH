
package com.raiji.rides.service;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.dto.RidePassengerResponse;
import com.raiji.rides.event.RideRequestCreatedEvent;
import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RidePassengerServiceTest {

    private static final String DRIVER_SUB = "driver-sub-123";
    private static final String PASSENGER_SUB = "passenger-sub-456";

    private RideRepository rideRepository;
    private RidePassengerRepository passengerRepository;
    private ApplicationEventPublisher eventPublisher;
    private RidePassengerService service;

    private UUID rideId;
    private Ride ride;

    @BeforeEach
    void setUp() {
        rideRepository = mock(RideRepository.class);
        passengerRepository = mock(RidePassengerRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new RidePassengerService(
                rideRepository,
                passengerRepository,
                eventPublisher
        );

        rideId = UUID.randomUUID();

        ride = new Ride();
        ReflectionTestUtils.setField(ride, "id", rideId);
        ride.setDriverSub(DRIVER_SUB);
        ride.setDriverName("Motorista");
        ride.setStatus(RideStatus.PLANNED);
        ride.setDepartureDate(LocalDateTime.now().plusDays(1));
        ride.setTotalSeats(4);
        ride.setAvailableSeats(4);

        when(rideRepository.findById(rideId))
                .thenReturn(Optional.of(ride));

        when(passengerRepository
                .existsByRideIdAndPassengerSubAndStatusInAndDeletedAtIsNull(
                        eq(rideId), eq(PASSENGER_SUB), any()))
                .thenReturn(false);

        when(passengerRepository
                .countByRideIdAndStatusInAndDeletedAtIsNull(eq(rideId), any()))
                .thenReturn(0L);

        when(passengerRepository.save(any(RidePassenger.class)))
                .thenAnswer(invocation -> {
                    RidePassenger passenger = invocation.getArgument(0);
                    ReflectionTestUtils.setField(
                            passenger, "id", UUID.randomUUID());
                    return passenger;
                });
    }

    @Test
    void deveCriarSolicitacaoPendente() {
        RidePassengerResponse response = service.request(
                rideId, PASSENGER_SUB, "João Silva");

        assertNotNull(response.id());
        assertEquals(rideId, response.rideId());
        assertEquals(PASSENGER_SUB, response.passengerSub());
        assertEquals("João Silva", response.passengerName());
        assertEquals(RidePassengerStatus.PENDING, response.status());
        assertNotNull(response.joinedAt());

        verify(passengerRepository).save(any(RidePassenger.class));
        verify(eventPublisher).publishEvent(
                any(RideRequestCreatedEvent.class));
    }

    @Test
    void deveRejeitarCaronaInexistente() {
        when(rideRepository.findById(rideId))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João")
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarCaronaNaoPlanejada() {
        ride.setStatus(RideStatus.IN_PROGRESS);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarPartidaNoPassado() {
        ride.setDepartureDate(LocalDateTime.now().minusHours(1));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarCaronaSemVagasDisponiveis() {
        ride.setAvailableSeats(0);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarSolicitacaoDuplicada() {
        when(passengerRepository
                .existsByRideIdAndPassengerSubAndStatusInAndDeletedAtIsNull(
                        eq(rideId), eq(PASSENGER_SUB), any()))
                .thenReturn(true);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarQuandoSolicitacoesAtivasAtingiremCapacidade() {
        when(passengerRepository
                .countByRideIdAndStatusInAndDeletedAtIsNull(eq(rideId), any()))
                .thenReturn(4L);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarListagemPorOutroMotorista() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.listForDriver(rideId, "outro-driver")
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(passengerRepository, never())
                .findByRideIdAndDeletedAtIsNull(rideId);
    }

    @Test
    void deveListarSolicitacoesDoProprioMotorista() {
        RidePassenger passenger = new RidePassenger();
        passenger.setRideId(rideId);
        passenger.setPassengerSub(PASSENGER_SUB);
        passenger.setPassengerName("João");

        when(passengerRepository.findByRideIdAndDeletedAtIsNull(rideId))
                .thenReturn(List.of(passenger));

        List<RidePassengerResponse> responses =
                service.listForDriver(rideId, DRIVER_SUB);

        assertEquals(1, responses.size());
        assertEquals("João", responses.get(0).passengerName());
    }
}
