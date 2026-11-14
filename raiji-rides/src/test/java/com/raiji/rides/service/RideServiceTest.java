package com.raiji.rides.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.rides.config.RidePricingProperties;
import com.raiji.rides.config.RideRulesProperties;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.pricing.PriceBreakdown;
import com.raiji.rides.dto.RideRequest;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.repository.RideRepository;
import com.raiji.users.domain.Vehicle;
import com.raiji.users.repository.VehicleRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class RideServiceTest {

    private static final String DRIVER_SUB = "driver-sub-123";

    private RideRepository rideRepository;
    private VehicleRepository vehicleRepository;
    private RideService rideService;

    @BeforeEach
    void setUp() {
        rideRepository = mock(RideRepository.class);
        vehicleRepository = mock(VehicleRepository.class);
        rideService = servico(false);
    }

    private RideService servico(boolean regraIntermunicipalLigada) {
        return new RideService(rideRepository, vehicleRepository,
                new RidePricingProperties(new BigDecimal("1.5")),
                new RideRulesProperties(regraIntermunicipalLigada),
                java.time.Clock.systemDefaultZone());
    }

    private void stubVeiculo(UUID vehicleId) {
        when(vehicleRepository.findByIdAndOwnerSubAndDeletedAtIsNull(vehicleId, DRIVER_SUB))
                .thenReturn(Optional.of(vehicleOwnedBy(vehicleId, DRIVER_SUB, 4)));
    }

    private Vehicle vehicleOwnedBy(String ownerSub, int seats) {
        return vehicleOwnedBy(UUID.randomUUID(), ownerSub, seats);
    }

    private Vehicle vehicleOwnedBy(UUID id, String ownerSub, int seats) {
        Vehicle v = new Vehicle();
        ReflectionTestUtils.setField(v, "id", id);
        v.setOwnerSub(ownerSub);
        v.setPlate("ABC1234");
        v.setType("CARRO");
        v.setModel("Fiat Uno");
        v.setPassengerSeats(seats);
        v.setActive(true);
        return v;
    }

    private RideRequest requestPaid(UUID vehicleId) {
        return new RideRequest(
                vehicleId, RideType.PASSENGER,
                "Fortaleza", "ce", "Sobral", "ce",
                LocalDateTime.now().plusDays(1),
                new BigDecimal("50.00"), 4, null);
    }

    private RideRequest requestFree(UUID vehicleId) {
        return new RideRequest(
                vehicleId, RideType.PASSENGER,
                "Fortaleza", "CE", "Sobral", "CE",
                LocalDateTime.now().plusDays(1),
                null, 2, null);
    }

    private RideRequest requestCargo(UUID vehicleId, CargoDetails cargo) {
        return new RideRequest(
                vehicleId, RideType.CARGO,
                "Fortaleza", "CE", "Sobral", "CE",
                LocalDateTime.now().plusDays(1),
                null, 2, cargo);
    }

    private void stubSave() {
        when(rideRepository.save(any(Ride.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------- create

    @Test
    void deveCriarCaronaPagaComStatusPlanned() {
        UUID vehicleId = UUID.randomUUID();
        when(vehicleRepository.findByIdAndOwnerSubAndDeletedAtIsNull(vehicleId, DRIVER_SUB))
                .thenReturn(Optional.of(vehicleOwnedBy(vehicleId, DRIVER_SUB, 4)));
        stubSave();

        RideResponse resp = rideService.create(requestPaid(vehicleId), DRIVER_SUB, "Fulano", false);

        assertEquals(RideStatus.PLANNED, resp.status());
        assertEquals(4, resp.availableSeats());
        assertEquals(4, resp.totalSeats());
        assertEquals(new BigDecimal("50.00"), resp.pricePerPassenger());
        assertEquals("CE", resp.originState());
        assertEquals("CE", resp.destinationState());
        assertEquals(DRIVER_SUB, resp.driverSub());
        assertNotNull(resp.vehicleId());
    }

    @Test
    void deveCriarCaronaGratuitaComPriceNull() {
        UUID vehicleId = UUID.randomUUID();
        when(vehicleRepository.findByIdAndOwnerSubAndDeletedAtIsNull(vehicleId, DRIVER_SUB))
                .thenReturn(Optional.of(vehicleOwnedBy(vehicleId, DRIVER_SUB, 4)));
        stubSave();

        RideResponse resp = rideService.create(requestFree(vehicleId), DRIVER_SUB, "Fulano", false);

        assertNull(resp.pricePerPassenger());
        assertEquals(2, resp.availableSeats());
    }

    @Test
    void deveRejeitarDepartureDateNoPassado() {
        UUID vehicleId = UUID.randomUUID();
        RideRequest req = new RideRequest(
                vehicleId, RideType.PASSENGER, "A", "CE", "B", "CE",
                LocalDateTime.now().minusHours(1), null, 2, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.create(req, DRIVER_SUB, "Fulano", false));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void deveRejeitarDepartureDateAcimaDe3Meses() {
        UUID vehicleId = UUID.randomUUID();
        RideRequest req = new RideRequest(
                vehicleId, RideType.PASSENGER, "A", "CE", "B", "CE",
                LocalDateTime.now().plusMonths(4), null, 2, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.create(req, DRIVER_SUB, "Fulano", false));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void deveRejeitarTotalSeatsAcimaDoVeiculo() {
        UUID vehicleId = UUID.randomUUID();
        when(vehicleRepository.findByIdAndOwnerSubAndDeletedAtIsNull(vehicleId, DRIVER_SUB))
                .thenReturn(Optional.of(vehicleOwnedBy(DRIVER_SUB, 3)));

        RideRequest req = new RideRequest(
                vehicleId, RideType.PASSENGER, "A", "CE", "B", "CE",
                LocalDateTime.now().plusDays(1), null, 5, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.create(req, DRIVER_SUB, "Fulano", false));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void deveRejeitarVeiculoDeOutroMotorista() {
        UUID vehicleId = UUID.randomUUID();
        when(vehicleRepository.findByIdAndOwnerSubAndDeletedAtIsNull(vehicleId, DRIVER_SUB))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.create(requestPaid(vehicleId), DRIVER_SUB, "Fulano", false));
        assertEquals(403, ex.getStatusCode().value());
        verify(rideRepository, never()).save(any());
    }

    @Test
    void deveRejeitarCargoSemCargoDetails() {
        UUID vehicleId = UUID.randomUUID();
        when(vehicleRepository.findByIdAndOwnerSubAndDeletedAtIsNull(vehicleId, DRIVER_SUB))
                .thenReturn(Optional.of(vehicleOwnedBy(DRIVER_SUB, 4)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.create(requestCargo(vehicleId, null), DRIVER_SUB, "Fulano", false));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void deveRejeitarCargoComCargoDetailsSemCapacidade() {
        UUID vehicleId = UUID.randomUUID();
        when(vehicleRepository.findByIdAndOwnerSubAndDeletedAtIsNull(vehicleId, DRIVER_SUB))
                .thenReturn(Optional.of(vehicleOwnedBy(DRIVER_SUB, 4)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.create(requestCargo(vehicleId, new CargoDetails()),
                        DRIVER_SUB, "Fulano", false));
        assertEquals(400, ex.getStatusCode().value());
    }

    // ------------------------------------------------- comissão da plataforma

    @Test
    void caronaPagaGravaAComissaoVigenteEMostraQuantoOMotoristaRecebe() {
        UUID vehicleId = UUID.randomUUID();
        stubVeiculo(vehicleId);
        stubSave();

        RideResponse resp = rideService.create(requestPaid(vehicleId), DRIVER_SUB, "Fulano", false);

        // 1,5% de R$ 50,00 = R$ 0,75; o passageiro paga 50,00 e o motorista recebe 49,25
        assertEquals(0, new BigDecimal("1.5").compareTo(resp.platformFeePercent()));
        assertEquals(new BigDecimal("0.75"), resp.platformFeePerPassenger());
        assertEquals(new BigDecimal("49.25"), resp.driverReceivesPerPassenger());
        assertEquals(new BigDecimal("50.00"), resp.pricePerPassenger());
    }

    @Test
    void caronaGratuitaNaoTemComissao() {
        UUID vehicleId = UUID.randomUUID();
        stubVeiculo(vehicleId);
        stubSave();

        RideResponse resp = rideService.create(requestFree(vehicleId), DRIVER_SUB, "Fulano", false);

        assertNull(resp.platformFeePerPassenger());
        assertNull(resp.driverReceivesPerPassenger());
    }

    @Test
    void comissaoArredondaAoCentavo() {
        // 1,5% de R$ 12,35 = 0,18525 -> 0,19 (meio para cima)
        PriceBreakdown p = rideService.previewPrice(new BigDecimal("12.35"));
        assertEquals(new BigDecimal("0.19"), p.platformFee());
        assertEquals(new BigDecimal("12.16"), p.driverReceives());
    }

    @Test
    void comissaoZeroDesliga() {
        PriceBreakdown p = PriceBreakdown.of(new BigDecimal("30"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("0.00"), p.platformFee());
        assertEquals(new BigDecimal("30.00"), p.driverReceives());
    }

    @Test
    void mudarOPrecoMantemAComissaoGravadaNaPublicacao() {
        UUID rideId = UUID.randomUUID();
        Ride ride = new Ride();
        ride.setDriverSub(DRIVER_SUB);
        ride.setStatus(RideStatus.PLANNED);
        ride.setPricePerPassenger(new BigDecimal("10.00"));
        ride.setPlatformFeePercent(new BigDecimal("2.00")); // publicada quando a comissão era 2%
        when(rideRepository.findById(rideId)).thenReturn(Optional.of(ride));
        stubSave();

        RideResponse resp = rideService.updatePrice(rideId, new BigDecimal("20.00"), DRIVER_SUB);

        assertEquals(new BigDecimal("0.40"), resp.platformFeePerPassenger());
    }

    @Test
    void previaRejeitaPrecoNegativo() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.previewPrice(new BigDecimal("-1")));
        assertEquals(400, ex.getStatusCode().value());
    }

    // ------------------------------------- regra CNPJ/MEI em carona intermunicipal

    @Test
    void regraDesligadaPessoaFisicaPublicaEntreCidades() {
        UUID vehicleId = UUID.randomUUID();
        stubVeiculo(vehicleId);
        stubSave();

        // Fortaleza -> Sobral, conta PF, regra desligada (padrão): permitido
        RideResponse resp = rideService.create(requestPaid(vehicleId), DRIVER_SUB, "Fulano", false);
        assertEquals(RideStatus.PLANNED, resp.status());
    }

    @Test
    void regraLigadaBloqueiaPessoaFisicaEntreCidades() {
        RideService comRegra = servico(true);
        UUID vehicleId = UUID.randomUUID();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> comRegra.create(requestPaid(vehicleId), DRIVER_SUB, "Fulano", false));
        assertEquals(403, ex.getStatusCode().value());
        verify(rideRepository, never()).save(any());
    }

    @Test
    void regraLigadaPermitePJEntreCidades() {
        RideService comRegra = servico(true);
        UUID vehicleId = UUID.randomUUID();
        stubVeiculo(vehicleId);
        stubSave();

        RideResponse resp = comRegra.create(requestPaid(vehicleId), DRIVER_SUB, "Fulano", true);
        assertEquals(RideStatus.PLANNED, resp.status());
    }

    @Test
    void regraLigadaPermitePessoaFisicaNaMesmaCidade() {
        RideService comRegra = servico(true);
        UUID vehicleId = UUID.randomUUID();
        stubVeiculo(vehicleId);
        stubSave();
        RideRequest urbana = new RideRequest(
                vehicleId, RideType.PASSENGER,
                "São Paulo", "SP", " sao  paulo", "sp",
                LocalDateTime.now().plusDays(1),
                new BigDecimal("15.00"), 3, null);

        RideResponse resp = comRegra.create(urbana, DRIVER_SUB, "Fulano", false);
        assertEquals(RideStatus.PLANNED, resp.status());
    }

    @Test
    void mesmaCidadeEmOutroEstadoEhIntermunicipal() {
        // Existem cidades homônimas em estados diferentes (ex.: Bom Jesus PI x RS)
        org.junit.jupiter.api.Assertions.assertTrue(
                RideService.isIntermunicipal("Bom Jesus", "PI", "Bom Jesus", "RS"));
    }

    // --------------------------------------------------------- updatePrice

    @Test
    void deveAlterarPrecoEmCaronaPlanned() {
        UUID rideId = UUID.randomUUID();
        Ride ride = new Ride();
        ride.setDriverSub(DRIVER_SUB);
        ride.setStatus(RideStatus.PLANNED);
        ride.setPricePerPassenger(new BigDecimal("10.00"));

        when(rideRepository.findById(rideId)).thenReturn(Optional.of(ride));
        stubSave();

        RideResponse resp = rideService.updatePrice(rideId, new BigDecimal("99.90"), DRIVER_SUB);

        assertEquals(new BigDecimal("99.90"), resp.pricePerPassenger());
        verify(rideRepository).save(ride);
    }

    @Test
    void naoDeveAlterarPrecoEmCaronaNaoPlanned() {
        UUID rideId = UUID.randomUUID();
        Ride ride = new Ride();
        ride.setDriverSub(DRIVER_SUB);
        ride.setStatus(RideStatus.IN_PROGRESS);

        when(rideRepository.findById(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.updatePrice(rideId, new BigDecimal("99.90"), DRIVER_SUB));
        assertEquals(409, ex.getStatusCode().value());
        verify(rideRepository, never()).save(any());
    }

    @Test
    void naoDeveAlterarPrecoDeOutroMotorista() {
        UUID rideId = UUID.randomUUID();
        Ride ride = new Ride();
        ride.setDriverSub("outro-sub");
        ride.setStatus(RideStatus.PLANNED);

        when(rideRepository.findById(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.updatePrice(rideId, new BigDecimal("99.90"), DRIVER_SUB));
        assertEquals(403, ex.getStatusCode().value());
        verify(rideRepository, never()).save(any());
    }

    @Test
    void naoDeveAlterarPrecoDeCaronaInexistente() {
        UUID rideId = UUID.randomUUID();
        when(rideRepository.findById(rideId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rideService.updatePrice(rideId, new BigDecimal("99.90"), DRIVER_SUB));
        assertEquals(404, ex.getStatusCode().value());
    }
}
