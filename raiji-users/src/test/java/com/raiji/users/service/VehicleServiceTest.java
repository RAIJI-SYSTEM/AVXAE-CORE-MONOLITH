package com.raiji.users.service;

import com.raiji.users.domain.Vehicle;
import com.raiji.users.dto.VehicleRequest;
import com.raiji.users.dto.VehicleResponse;
import com.raiji.users.repository.VehicleRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VehicleServiceTest {

    private VehicleRepository vehicleRepository;
    private VehicleService vehicleService;

    @BeforeEach
    void setUp() {
        vehicleRepository = mock(VehicleRepository.class);
        vehicleService = new VehicleService(vehicleRepository);
    }

    private VehicleRequest request(String plate, String model) {
        return new VehicleRequest(
                plate, " CARRO ", model, " Branco ", 2020, 4, 300
        );
    }

    private Vehicle vehicle(String owner, String plate, String model) {
        Vehicle vehicle = new Vehicle();
        vehicle.setOwnerSub(owner);
        vehicle.setPlate(plate);
        vehicle.setType("CARRO");
        vehicle.setModel(model);
        vehicle.setColor("Branco");
        vehicle.setYear(2020);
        vehicle.setPassengerSeats(4);
        vehicle.setCargoVolumeLiters(300);
        return vehicle;
    }

    @Test
    void deveCadastrarVeiculoNormalizandoPlacaECampos() {
        when(vehicleRepository.existsByPlate("ABC1234")).thenReturn(false);
        when(vehicleRepository.save(any(Vehicle.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponse response = vehicleService.create(
                request(" abc1234 ", " Fiat Uno "),
                "usuario-123"
        );

        assertEquals("usuario-123", response.ownerSub());
        assertEquals("ABC1234", response.plate());
        assertEquals("CARRO", response.type());
        assertEquals("Fiat Uno", response.model());
        assertEquals("Branco", response.color());
        assertEquals(2020, response.year());
        assertEquals(4, response.passengerSeats());
        assertEquals(300, response.cargoVolumeLiters());
        assertTrue(response.active());

        verify(vehicleRepository).existsByPlate("ABC1234");
        verify(vehicleRepository).save(any(Vehicle.class));
    }

    @Test
    void naoDeveCadastrarPlacaDuplicada() {
        when(vehicleRepository.existsByPlate("ABC1234")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> vehicleService.create(
                        request("abc1234", "Fiat Uno"),
                        "usuario-123"
                )
        );

        assertEquals(409, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("placa"));
        verify(vehicleRepository, never()).save(any(Vehicle.class));
    }

    @Test
    void deveListarVeiculosDoProprietario() {
        Vehicle vehicle = vehicle("usuario-123", "ABC1234", "Fiat Uno");

        when(vehicleRepository.findByOwnerSubAndDeletedAtIsNull("usuario-123"))
                .thenReturn(List.of(vehicle));

        List<VehicleResponse> responses =
                vehicleService.listByOwner("usuario-123");

        assertEquals(1, responses.size());
        assertEquals("usuario-123", responses.get(0).ownerSub());
        assertEquals("ABC1234", responses.get(0).plate());

        verify(vehicleRepository)
                .findByOwnerSubAndDeletedAtIsNull("usuario-123");
    }

    @Test
    void deveRetornarListaVaziaQuandoProprietarioNaoTemVeiculos() {
        when(vehicleRepository.findByOwnerSubAndDeletedAtIsNull("usuario-123"))
                .thenReturn(List.of());

        List<VehicleResponse> responses =
                vehicleService.listByOwner("usuario-123");

        assertTrue(responses.isEmpty());
        verify(vehicleRepository)
                .findByOwnerSubAndDeletedAtIsNull("usuario-123");
    }

    @Test
    void deveBuscarVeiculoDoProprietario() {
        UUID id = UUID.randomUUID();
        Vehicle vehicle = vehicle("usuario-123", "ABC1234", "Fiat Uno");

        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(id, "usuario-123"))
                .thenReturn(Optional.of(vehicle));

        VehicleResponse response = vehicleService.findById(id, "usuario-123");

        assertEquals("usuario-123", response.ownerSub());
        assertEquals("ABC1234", response.plate());
        assertEquals("Fiat Uno", response.model());
    }

    @Test
    void deveRetornar404QuandoVeiculoNaoPertenceAoUsuario() {
        UUID id = UUID.randomUUID();

        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(id, "usuario-123"))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> vehicleService.findById(id, "usuario-123")
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void deveAtualizarVeiculoDoProprietario() {
        UUID id = UUID.randomUUID();
        Vehicle vehicle = vehicle("usuario-123", "ABC1234", "Fiat Uno");

        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(id, "usuario-123"))
                .thenReturn(Optional.of(vehicle));

        when(vehicleRepository.existsByPlateAndIdNot("XYZ5678", id))
                .thenReturn(false);

        when(vehicleRepository.save(any(Vehicle.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponse response = vehicleService.update(
                id,
                "usuario-123",
                request(" xyz5678 ", " Honda Civic ")
        );

        assertEquals("XYZ5678", response.plate());
        assertEquals("Honda Civic", response.model());
        assertEquals("usuario-123", response.ownerSub());

        verify(vehicleRepository).save(vehicle);
    }

    @Test
    void naoDeveAtualizarParaPlacaDeOutroVeiculo() {
        UUID id = UUID.randomUUID();
        Vehicle vehicle = vehicle("usuario-123", "ABC1234", "Fiat Uno");

        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(id, "usuario-123"))
                .thenReturn(Optional.of(vehicle));

        when(vehicleRepository.existsByPlateAndIdNot("XYZ5678", id))
                .thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> vehicleService.update(
                        id,
                        "usuario-123",
                        request("xyz5678", "Honda Civic")
                )
        );

        assertEquals(409, exception.getStatusCode().value());
        verify(vehicleRepository, never()).save(any(Vehicle.class));
    }

    @Test
    void naoDeveAtualizarVeiculoInexistenteOuDeOutroProprietario() {
        UUID id = UUID.randomUUID();

        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(id, "usuario-123"))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> vehicleService.update(
                        id,
                        "usuario-123",
                        request("XYZ5678", "Honda Civic")
                )
        );

        assertEquals(404, exception.getStatusCode().value());
        verify(vehicleRepository, never()).save(any(Vehicle.class));
    }

    @Test
    void deveRealizarExclusaoLogica() {
        UUID id = UUID.randomUUID();
        Vehicle vehicle = vehicle("usuario-123", "ABC1234", "Fiat Uno");

        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(id, "usuario-123"))
                .thenReturn(Optional.of(vehicle));

        vehicleService.deactivate(id, "usuario-123");

        assertFalse(vehicle.isActive());
        assertNotNull(vehicle.getDeletedAt());
        verify(vehicleRepository).save(vehicle);
        verify(vehicleRepository, never()).delete(any(Vehicle.class));
    }

    @Test
    void naoDeveExcluirVeiculoInexistenteOuDeOutroProprietario() {
        UUID id = UUID.randomUUID();

        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(id, "usuario-123"))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> vehicleService.deactivate(id, "usuario-123")
        );

        assertEquals(404, exception.getStatusCode().value());
        verify(vehicleRepository, never()).save(any(Vehicle.class));
    }
}