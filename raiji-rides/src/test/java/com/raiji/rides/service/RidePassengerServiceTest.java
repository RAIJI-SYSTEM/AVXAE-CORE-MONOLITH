
package com.raiji.rides.service;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.BaggageType;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.dto.BaggageItemRequest;
import com.raiji.rides.dto.RideDecisionResponse;
import com.raiji.rides.dto.RidePassengerResponse;
import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.event.RideRequestCreatedEvent;
import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
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
                rideId, PASSENGER_SUB, "João Silva", bagagemValida());

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
                () -> service.request(rideId, PASSENGER_SUB, "João", bagagemValida())
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarCaronaNaoPlanejada() {
        ride.setStatus(RideStatus.IN_PROGRESS);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João", bagagemValida())
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarPartidaNoPassado() {
        ride.setDepartureDate(LocalDateTime.now().minusHours(1));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João", bagagemValida())
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarCaronaSemVagasDisponiveis() {
        ride.setAvailableSeats(0);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João", bagagemValida())
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
                () -> service.request(rideId, PASSENGER_SUB, "João", bagagemValida())
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
                () -> service.request(rideId, PASSENGER_SUB, "João", bagagemValida())
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void devePersistirBagagemNaMesmaTransacao() {
        RidePassengerResponse response = service.request(
                rideId, PASSENGER_SUB, "João Silva", bagagemValida());

        ArgumentCaptor<RidePassenger> captor =
                ArgumentCaptor.forClass(RidePassenger.class);
        verify(passengerRepository).save(captor.capture());

        RidePassenger salvo = captor.getValue();
        assertEquals(1, salvo.getBaggage().size());
        assertEquals(BaggageType.MALA, salvo.getBaggage().get(0).getType());
        assertEquals(1, salvo.getBaggage().get(0).getQuantity());
        assertEquals(60, salvo.getBaggage().get(0).getLengthCm());
        assertSame(salvo, salvo.getBaggage().get(0).getRidePassenger());

        assertEquals(1, response.baggage().size());
        assertEquals(BaggageType.MALA, response.baggage().get(0).type());
        assertEquals("Mala de bordo", response.baggage().get(0).description());
    }

    @Test
    void deveRejeitarBagagemAusenteOuVazia() {
        ResponseStatusException semBagagem = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João", null)
        );
        assertEquals(HttpStatus.BAD_REQUEST, semBagagem.getStatusCode());

        ResponseStatusException bagagemVazia = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, PASSENGER_SUB, "João", List.of())
        );
        assertEquals(HttpStatus.BAD_REQUEST, bagagemVazia.getStatusCode());

        verify(passengerRepository, never()).save(any());
    }

    private static List<BaggageItemRequest> bagagemValida() {
        return List.of(new BaggageItemRequest(
                BaggageType.MALA, 1, 60, 40, 30, "Mala de bordo"));
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

    // =========================================================================
    // S2-02 / RF08 — decide(): aceite/recusa do motorista
    // =========================================================================

    private RidePassenger solicitacaoPendente() {
        RidePassenger passenger = new RidePassenger();
        ReflectionTestUtils.setField(passenger, "id", UUID.randomUUID());
        passenger.setRideId(rideId);
        passenger.setPassengerSub(PASSENGER_SUB);
        passenger.setPassengerName("João");

        when(passengerRepository.findById(passenger.getId()))
                .thenReturn(Optional.of(passenger));

        return passenger;
    }

    @Test
    void deveConfirmarSolicitacaoNoAccept() {
        RidePassenger pendente = solicitacaoPendente();

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB, RideDecision.ACCEPT, null);

        assertEquals(pendente.getId(), response.id());
        assertEquals(RidePassengerStatus.CONFIRMED, response.status());
        assertNotNull(response.confirmedAt());

        ArgumentCaptor<RidePassenger> captor =
                ArgumentCaptor.forClass(RidePassenger.class);
        verify(passengerRepository).save(captor.capture());
        assertEquals(RidePassengerStatus.CONFIRMED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getConfirmedAt());

        // vagas decrementadas: fixture começa com availableSeats = 4
        assertEquals(3, ride.getAvailableSeats());
        verify(rideRepository).save(ride);
        verify(eventPublisher).publishEvent(any(RideDecisionEvent.class));
    }

    @Test
    void deveCancelarComMotivoNoReject() {
        RidePassenger pendente = solicitacaoPendente();

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB,
                RideDecision.REJECT, "Bagagem excede o porta-malas.");

        assertEquals(RidePassengerStatus.CANCELLED, response.status());
        assertNull(response.confirmedAt());

        ArgumentCaptor<RidePassenger> captor =
                ArgumentCaptor.forClass(RidePassenger.class);
        verify(passengerRepository).save(captor.capture());
        assertEquals(RidePassengerStatus.CANCELLED, captor.getValue().getStatus());
        assertEquals(
                "Bagagem excede o porta-malas.",
                captor.getValue().getCancellationReason());

        // vagas permanecem intactas no REJECT
        assertEquals(4, ride.getAvailableSeats());
        verify(eventPublisher).publishEvent(any(RideDecisionEvent.class));
    }

    @Test
    void deveRejeitarRejectSemMotivo() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, UUID.randomUUID(), DRIVER_SUB,
                        RideDecision.REJECT, "   ")
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarDecisaoPorOutroMotorista() {
        RidePassenger pendente = solicitacaoPendente();

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), "outro-driver",
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarSolicitacaoJaDecidida() {
        RidePassenger pendente = solicitacaoPendente();
        pendente.confirm();

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.REJECT, "Qualquer motivo")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarAcceptSemVagaDisponivel() {
        RidePassenger pendente = solicitacaoPendente();
        ride.setAvailableSeats(0);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarSolicitacaoInexistenteNaDecisao() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, UUID.randomUUID(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }
}
