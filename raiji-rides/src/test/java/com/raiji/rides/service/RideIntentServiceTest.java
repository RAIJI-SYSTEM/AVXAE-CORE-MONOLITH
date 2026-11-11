
package com.raiji.rides.service;

import com.raiji.rides.domain.RideIntent;
import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentStatus;
import com.raiji.rides.domain.enums.RideIntentType;
import com.raiji.rides.repository.RideIntentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RideIntentServiceTest {

    @Mock
    private RideIntentRepository repository;

    @InjectMocks
    private RideIntentService service;

    @Test
    void deveCriarSolicitacaoDePassageiro() {
        when(repository.save(any(RideIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RideIntent result = service.create(
                "passenger-123",
                RideIntentType.PASSENGER_REQUEST,
                RideIntentMode.ROUTE,
                "Encanto",
                "RN",
                -6.12,
                -38.20,
                "Pau dos Ferros",
                "RN",
                -6.11,
                -38.20,
                null
        );

        assertEquals("passenger-123", result.getUserSub());
        assertEquals(RideIntentType.PASSENGER_REQUEST, result.getType());
        assertEquals(RideIntentMode.ROUTE, result.getMode());
        assertEquals(RideIntentStatus.ACTIVE, result.getStatus());
        assertNotNull(result.getOriginLocation());
        assertNotNull(result.getDestinationLocation());
        assertNotNull(result.getExpiresAt());

        assertEquals(-38.20, result.getOriginLocation().getX());
        assertEquals(-6.12, result.getOriginLocation().getY());

        verify(repository).save(any(RideIntent.class));
    }

    @Test
    void deveCriarOfertaLivreDeMotorista() {
        when(repository.save(any(RideIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RideIntent result = service.create(
                "driver-123",
                RideIntentType.DRIVER_OFFER,
                RideIntentMode.FREE,
                "Encanto",
                "RN",
                -6.12,
                -38.20,
                null,
                null,
                null,
                null,
                null
        );

        assertEquals(RideIntentType.DRIVER_OFFER, result.getType());
        assertEquals(RideIntentMode.FREE, result.getMode());
        assertNull(result.getDestinationLocation());
        assertNull(result.getDestinationCity());

        verify(repository).save(any(RideIntent.class));
    }

    @Test
    void naoDevePermitirFreeParaPassageiro() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create(
                        "passenger-123",
                        RideIntentType.PASSENGER_REQUEST,
                        RideIntentMode.FREE,
                        "Encanto",
                        "RN",
                        -6.12,
                        -38.20,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        verify(repository, never()).save(any());
    }

    @Test
    void naoDevePermitirLatitudeInvalida() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create(
                        "driver-123",
                        RideIntentType.DRIVER_OFFER,
                        RideIntentMode.FREE,
                        "Encanto",
                        "RN",
                        100.0,
                        -38.20,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        verify(repository, never()).save(any());
    }

    @Test
    void nearbyNaoDeveRetornarIntencaoDoProprioUsuario() {
        RideIntent propria = new RideIntent();
        propria.setUserSub("user-123");

        RideIntent outra = new RideIntent();
        outra.setUserSub("user-456");

        when(repository.findNearby(
                anyString(), anyString(), anyDouble(), anyDouble(), anyDouble()
        )).thenReturn(List.of(propria, outra));

        List<RideIntent> result = service.findNearby(
                "user-123",
                RideIntentType.DRIVER_OFFER,
                RideIntentMode.FREE,
                -6.12,
                -38.20,
                5000
        );

        assertEquals(1, result.size());
        assertEquals("user-456", result.get(0).getUserSub());
    }

    @Test
    void deveCancelarIntencaoDoProprietario() {
        UUID id = UUID.randomUUID();

        RideIntent intent = new RideIntent();
        intent.setUserSub("user-123");
        intent.setStatus(RideIntentStatus.ACTIVE);

        when(repository.findById(id)).thenReturn(Optional.of(intent));
        when(repository.save(any(RideIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> service.cancel("user-123", id));

        assertEquals(RideIntentStatus.CANCELLED, intent.getStatus());
        verify(repository).save(intent);
    }

    @Test
    void naoDeveCancelarIntencaoDeOutroUsuario() {
        UUID id = UUID.randomUUID();

        RideIntent intent = new RideIntent();
        intent.setUserSub("user-456");
        intent.setStatus(RideIntentStatus.ACTIVE);

        when(repository.findById(id)).thenReturn(Optional.of(intent));

        assertThrows(IllegalArgumentException.class,
                () -> service.cancel("user-123", id));

        verify(repository, never()).save(any());
    }

    @Test
    void deveLancarErroAoCancelarIntencaoInexistente() {
        UUID id = UUID.randomUUID();

        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.cancel("user-123", id));

        verify(repository, never()).save(any());
    }

    @Test
    void naoDeveCancelarIntencaoJaCancelada() {
        UUID id = UUID.randomUUID();

        RideIntent intent = new RideIntent();
        intent.setUserSub("user-123");
        intent.setStatus(RideIntentStatus.CANCELLED);

        when(repository.findById(id)).thenReturn(Optional.of(intent));

        assertThrows(IllegalStateException.class,
                () -> service.cancel("user-123", id));

        verify(repository, never()).save(any());
    }
}
