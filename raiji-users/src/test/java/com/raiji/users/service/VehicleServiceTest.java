
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
                plate, "CARRO", model, "Branco", 2020, 4, 300
        );
    }

    @Test
    void deveCadastrarVeiculoEPadronizarPlaca() {
        when(vehicleRepository.existsByPlate("ABC1234"))
                .thenReturn(false);

        when(vehicleRepository.save(any(Vehicle.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponse response = vehicleService.create(
                request(" abc1234 ", "Fiat Uno"),
                "usuario-123"
        );

        assertEquals("usuario-123", response.ownerSub());
        assertEquals("ABC1234", response.plate());
        assertEquals("Fiat Uno", response.model());
        assertTrue(response.active());

        verify(vehicleRepository).save(any(Vehicle.class));
    }

    @Test
    void naoDeveCadastrarPlacaDuplicada() {
        when(vehicleRepository.existsByPlate("ABC1234"))
                .thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> vehicleService.create(
                        request("abc1234", "Fiat Uno"),
                        "usuario-123"
                )
        );

        assertEquals(409, exception.getStatusCode().value());
        verify(vehicleRepository, never()).save(any(Vehicle.class));
    }

    @Test
    void deveListarSomenteVeiculosAtivosDoProprietarioConsultado() {
        Vehicle vehicle = new Vehicle();
        vehicle.setOwnerSub("usuario-123");
        vehicle.setPlate("ABC1234");
        vehicle.setType("CARRO");
        vehicle.setModel("Fiat Uno");

        when(vehicleRepository.findByOwnerSubAndDeletedAtIsNull("usuario-123"))
                .thenReturn(List.of(vehicle));

        List<VehicleResponse> responses =
                vehicleService.listByOwner("usuario-123");

        assertEquals(1, responses.size());
        assertEquals("usuario-123", responses.get(0).ownerSub());

        verify(vehicleRepository)
                .findByOwnerSubAndDeletedAtIsNull("usuario-123");
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

        Vehicle vehicle = new Vehicle();
        vehicle.setOwnerSub("usuario-123");
        vehicle.setPlate("ABC1234");
        vehicle.setType("CARRO");
        vehicle.setModel("Fiat Uno");

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
                request("xyz5678", "Honda Civic")
        );

        assertEquals("XYZ5678", response.plate());
        assertEquals("Honda Civic", response.model());
        assertEquals("usuario-123", response.ownerSub());

        verify(vehicleRepository).save(vehicle);
    }

    @Test
    void deveRealizarExclusaoLogica() {
        UUID id = UUID.randomUUID();

        Vehicle vehicle = new Vehicle();
        vehicle.setOwnerSub("usuario-123");
        vehicle.setPlate("ABC1234");
        vehicle.setType("CARRO");
        vehicle.setModel("Fiat Uno");

        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(id, "usuario-123"))
                .thenReturn(Optional.of(vehicle));

        vehicleService.deactivate(id, "usuario-123");

        assertFalse(vehicle.isActive());
        assertNotNull(vehicle.getDeletedAt());

        verify(vehicleRepository).save(vehicle);
    }
}
