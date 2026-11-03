
package com.raiji.rides.service;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.BaggageType;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.dto.BaggageItemRequest;
import com.raiji.rides.dto.RideCancelResponse;
import com.raiji.rides.dto.RideDecisionResponse;
import com.raiji.rides.dto.RidePassengerResponse;
import com.raiji.rides.event.RideCancelledEvent;
import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.event.RideRequestCreatedEvent;
import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;
import com.raiji.users.domain.Vehicle;
import com.raiji.users.repository.VehicleRepository;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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
    private VehicleRepository vehicleRepository;
    private RidePassengerService service;

    private UUID rideId;
    private Ride ride;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        rideRepository = mock(RideRepository.class);
        passengerRepository = mock(RidePassengerRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        vehicleRepository = mock(VehicleRepository.class);

        service = new RidePassengerService(
                rideRepository,
                passengerRepository,
                eventPublisher,
                vehicleRepository
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
        ride.setVehicleId(UUID.randomUUID());

        when(rideRepository.findById(rideId))
                .thenReturn(Optional.of(ride));

        when(rideRepository.findByIdForUpdate(rideId))
                .thenReturn(Optional.of(ride));

        vehicle = new Vehicle();
        vehicle.setOwnerSub(DRIVER_SUB);
        vehicle.setCargoVolumeLiters(400);
        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(vehicle));

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
                BaggageType.MALA, 1, 60, 40, 30, null, "Mala de bordo"));
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

    // =========================================================================
    // S2-03 / RN03+RN12 — trava de capacidade (lock pessimista, peso, volume)
    // =========================================================================

    private static Baggage bagagem(String pesoKg, int quantidade,
                                   int lengthCm, int widthCm, int heightCm) {
        Baggage b = new Baggage();
        b.setType(BaggageType.MALA);
        b.setQuantity(quantidade);
        b.setLengthCm(lengthCm);
        b.setWidthCm(widthCm);
        b.setHeightCm(heightCm);
        if (pesoKg != null) {
            b.setWeightKg(new BigDecimal(pesoKg));
        }
        return b;
    }

    private RidePassenger solicitacaoPendenteComBagagem(Baggage... itens) {
        RidePassenger passenger = solicitacaoPendente();
        for (Baggage b : itens) {
            passenger.addBaggage(b);
        }
        // em produção o candidato consta na listagem da carona (mesma
        // transação); no teste unitário garantimos o mesmo comportamento
        when(passengerRepository.findByRideIdAndDeletedAtIsNull(rideId))
                .thenReturn(List.of(passenger));
        return passenger;
    }

    private RidePassenger confirmadoComBagagem(Baggage... itens) {
        RidePassenger passenger = new RidePassenger();
        ReflectionTestUtils.setField(passenger, "id", UUID.randomUUID());
        passenger.setRideId(rideId);
        passenger.setPassengerSub("confirmado-" + UUID.randomUUID());
        passenger.setPassengerName("Passageiro Confirmado");
        passenger.confirm();
        for (Baggage b : itens) {
            passenger.addBaggage(b);
        }
        return passenger;
    }

    private void comCargoDetails(String maxWeightKg) {
        CargoDetails cargo = new CargoDetails();
        cargo.setMaxWeightKg(new BigDecimal(maxWeightKg));
        ride.setCargoDetails(cargo);
    }

    @Test
    void deveUsarLockPessimistaAoDecidir() {
        RidePassenger pendente = solicitacaoPendente();

        service.decide(rideId, pendente.getId(), DRIVER_SUB,
                RideDecision.ACCEPT, null);

        verify(rideRepository).findByIdForUpdate(rideId);
    }

    @Test
    void deveAceitarPesoExatamenteNoLimite() {
        comCargoDetails("20");
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem("10", 2, 40, 30, 20)); // 10 kg × 2 = 20 kg == limite

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB, RideDecision.ACCEPT, null);

        assertEquals(RidePassengerStatus.CONFIRMED, response.status());
        verify(passengerRepository).save(any(RidePassenger.class));
    }

    @Test
    void deveRejeitarPesoUmAcimaDoLimite() {
        comCargoDetails("20");
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem("20.5", 1, 40, 30, 20)); // 20.5 kg > 20 kg

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveSomarBagagensConfirmadasAoDecidir() {
        comCargoDetails("30");
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem("16", 1, 40, 30, 20));
        RidePassenger confirmado = confirmadoComBagagem(
                bagagem("15", 1, 40, 30, 20));
        when(passengerRepository.findByRideIdAndDeletedAtIsNull(rideId))
                .thenReturn(List.of(confirmado, pendente)); // 15 + 16 = 31 kg > 30 kg

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveAceitarVolumeExatamenteNoLimite() {
        vehicle.setCargoVolumeLiters(100);
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem(null, 2, 50, 40, 25)); // 50 L × 2 = 100 L == limite

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB, RideDecision.ACCEPT, null);

        assertEquals(RidePassengerStatus.CONFIRMED, response.status());
    }

    @Test
    void deveRejeitarVolumeAcimaDaCapacidade() {
        vehicle.setCargoVolumeLiters(100);
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem(null, 2, 50, 40, 26)); // 52 L × 2 = 104 L > 100 L

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveIgnorarTravaDeCargaSemCapacidadeDefinida() {
        vehicle.setCargoVolumeLiters(null); // veículo sem capacidade declarada
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem("9999", 9, 999, 999, 999)); // carona sem cargoDetails

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB, RideDecision.ACCEPT, null);

        assertEquals(RidePassengerStatus.CONFIRMED, response.status());
    }

    // =========================================================================
    // S2-04 / RF09+RN06 — cancel() pelo passageiro (ou motorista dono)
    // =========================================================================

    /** Reserva CONFIRMED (vaga já ocupada) pronta para cancelamento. */
    private RidePassenger solicitacaoConfirmada() {
        RidePassenger passenger = new RidePassenger();
        ReflectionTestUtils.setField(passenger, "id", UUID.randomUUID());
        passenger.setRideId(rideId);
        passenger.setPassengerSub(PASSENGER_SUB);
        passenger.setPassengerName("João");
        passenger.confirm();

        when(passengerRepository.findById(passenger.getId()))
                .thenReturn(Optional.of(passenger));

        return passenger;
    }

    @Test
    void deveCancelarReservaConfirmadaLiberandoVaga() {
        ride.setTotalSeats(4);
        ride.setAvailableSeats(2);
        RidePassenger confirmada = solicitacaoConfirmada();

        RideCancelResponse response = service.cancel(
                rideId, confirmada.getId(), PASSENGER_SUB, "Imprevisto pessoal");

        assertEquals(RidePassengerStatus.CANCELLED, response.status());
        assertEquals("Imprevisto pessoal", response.cancellationReason());
        assertNotNull(response.cancelledAt(), "critério de aceite: CANCELLED com data");
        assertTrue(response.seatReleased());

        // vaga liberada na hora: availableSeats++
        assertEquals(3, ride.getAvailableSeats());
        verify(rideRepository).save(ride);
        verify(passengerRepository).save(any(RidePassenger.class));
        verify(eventPublisher).publishEvent(any(RideCancelledEvent.class));
    }

    @Test
    void deveCancelarReservaPendenteSemAlterarVagas() {
        ride.setTotalSeats(4);
        ride.setAvailableSeats(4);
        RidePassenger pendente = solicitacaoPendente();

        RideCancelResponse response = service.cancel(
                rideId, pendente.getId(), PASSENGER_SUB, null);

        assertEquals(RidePassengerStatus.CANCELLED, response.status());
        assertFalse(response.seatReleased());
        assertEquals(4, ride.getAvailableSeats(), "PENDING não altera availableSeats");
        verify(eventPublisher).publishEvent(any(RideCancelledEvent.class));
    }

    @Test
    void devePermitirMotoristaDonoCancelarReserva() {
        ride.setTotalSeats(4);
        ride.setAvailableSeats(2);
        RidePassenger confirmada = solicitacaoConfirmada();

        RideCancelResponse response = service.cancel(
                rideId, confirmada.getId(), DRIVER_SUB, "Veículo em manutenção");

        assertEquals(RidePassengerStatus.CANCELLED, response.status());
        assertTrue(response.seatReleased());
        assertEquals(3, ride.getAvailableSeats());
    }

    @Test
    void deveRejeitarCancelamentoPorOutroPassageiro() {
        RidePassenger confirmada = solicitacaoConfirmada();

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(rideId, confirmada.getId(),
                        "outro-passageiro", "motivo")
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
        verify(rideRepository, never()).save(any());
    }

    @Test
    void deveRejeitarCancelamentoAposInicioDaViagem() {
        ride.setStatus(RideStatus.IN_PROGRESS);
        RidePassenger confirmada = solicitacaoConfirmada();

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(rideId, confirmada.getId(),
                        PASSENGER_SUB, "motivo")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarReservaJaCancelada() {
        RidePassenger confirmada = solicitacaoConfirmada();
        confirmada.cancel("cancelamento anterior");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(rideId, confirmada.getId(),
                        PASSENGER_SUB, "de novo")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarCancelamentoDeReservaInexistente() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(rideId, UUID.randomUUID(),
                        PASSENGER_SUB, "motivo")
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }
}
