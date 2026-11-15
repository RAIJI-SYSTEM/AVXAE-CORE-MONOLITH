
package com.raiji.deliveries.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.deliveries.Delivery;
import com.raiji.deliveries.PackageDetails;
import com.raiji.deliveries.PackageSize;
import com.raiji.deliveries.dto.DeliveryCreateRequest;
import com.raiji.deliveries.dto.PackageRequest;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.deliveries.repository.DeliveryStatusHistoryRepository;
import com.raiji.rides.cargo.CargoCapacityService;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.rides.repository.RideRepository;
import com.raiji.users.company.CompanyMembership;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock
    private DeliveryRepository deliveries;

    @Mock
    private DeliveryStatusHistoryRepository history;

    @Mock
    private RideRepository rides;

    @Mock
    private CargoCapacityService capacity;

    @Mock
    private CompanyMembership membership;

    @Mock
    private DeliveryNotifier notifier;

    @Mock
    private Clock clock;

    @InjectMocks
    private DeliveryService service;

    // =========================================================
    // DRIVING
    // =========================================================

    @Test
    void drivingSemCaronasDeveRetornarListaVazia() {
        String driverSub = "motorista-123";

        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                driverSub,
                List.of(
                        RideStatus.PLANNED,
                        RideStatus.IN_PROGRESS,
                        RideStatus.COMPLETED
                )
        )).thenReturn(List.of());

        List<?> resultado = service.driving(driverSub);

        assertTrue(resultado.isEmpty());
    }

    @Test
    void drivingComCaronasSemEncomendasDeveRetornarListaVazia() {
        String driverSub = "motorista-123";
        UUID rideId = UUID.randomUUID();

        Ride ride = mock(Ride.class);
        when(ride.getId()).thenReturn(rideId);

        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                eq(driverSub), anyList()
        )).thenReturn(List.of(ride));

        when(deliveries.findByRideIds(List.of(rideId)))
                .thenReturn(List.of());

        List<?> resultado = service.driving(driverSub);

        assertTrue(resultado.isEmpty());
        verify(rides).findAllById(List.of());
    }

    @Test
    void drivingComCaronasEEncomendasDeveRetornarEncomendas() {
        String driverSub = "motorista-123";
        UUID rideId = UUID.randomUUID();

        Ride ride = mock(Ride.class);
        Delivery delivery = mock(Delivery.class);
        PackageDetails pkg = mock(PackageDetails.class);

        when(ride.getId()).thenReturn(rideId);
        when(ride.getStatus()).thenReturn(RideStatus.PLANNED);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getPkg()).thenReturn(pkg);
        when(pkg.getSize()).thenReturn(PackageSize.values()[0]);

        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                eq(driverSub), anyList()
        )).thenReturn(List.of(ride));

        when(deliveries.findByRideIds(List.of(rideId)))
                .thenReturn(List.of(delivery));

        when(rides.findAllById(List.of(rideId)))
                .thenReturn(List.of(ride));

        List<?> resultado = service.driving(driverSub);

        assertEquals(1, resultado.size());
    }

    // =========================================================
    // REQUEST
    // =========================================================

    @Test
    void requestSemTrechoPersonalizadoDeveCriarEncomendaNaRotaCompleta() {
        UUID rideId = UUID.randomUUID();
        String senderSub = "remetente-123";

        Ride ride = mock(Ride.class);

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        when(ride.getDeletedAt()).thenReturn(null);
        when(ride.getVisibility()).thenReturn(RideVisibility.PUBLIC);
        when(ride.getDriverSub()).thenReturn("motorista-123");
        when(ride.carriesPackages()).thenReturn(true);
        when(ride.openForBookings()).thenReturn(true);
        when(ride.getDepartureDate())
                .thenReturn(LocalDateTime.now(Clock.systemUTC()).plusDays(1));
        when(ride.getPricePerPackage()).thenReturn(new BigDecimal("100.00"));
        when(ride.getPlatformFeePercent()).thenReturn(new BigDecimal("10.00"));
        when(ride.getDestinationCity()).thenReturn("Natal");
        when(ride.getId()).thenReturn(rideId);
        when(ride.getStatus()).thenReturn(RideStatus.PLANNED);

        when(clock.instant()).thenReturn(Instant.now());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        PackageRequest packageRequest = new PackageRequest(
                PackageSize.SMALL,
                "Documentos",
                new BigDecimal("2.00"),
                null,
                null,
                null,
                false,
                new BigDecimal("50.00"),
                "Destinatário",
                "84999999999"
        );

        DeliveryCreateRequest body = new DeliveryCreateRequest(
                packageRequest, null, null, null, "Remetente"
        );

        when(deliveries.save(any(Delivery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var resultado = service.request(
                rideId, senderSub, "Remetente", body
        );

        assertNotNull(resultado);
        assertEquals(new BigDecimal("100.00"), resultado.price());
        assertTrue(resultado.fullRoute());

        verify(capacity).assertFits(eq(ride), any(), isNull());
        verify(deliveries).save(any(Delivery.class));
        verify(history).save(any());
    }

    @Test
    void requestComCaronaInexistenteDeveRetornar404() {
        UUID rideId = UUID.randomUUID();

        when(rides.findById(rideId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, "remetente-123", "Remetente", null)
        );

        assertEquals(404, exception.getStatusCode().value());
        verify(deliveries, never()).save(any());
    }

    @Test
    void requestPeloProprioMotoristaDeveRetornar409() {
        UUID rideId = UUID.randomUUID();
        Ride ride = mock(Ride.class);

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        when(ride.getDeletedAt()).thenReturn(null);
        when(ride.getVisibility()).thenReturn(RideVisibility.PUBLIC);
        when(ride.getDriverSub()).thenReturn("motorista-123");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, "motorista-123", "Motorista", null)
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void requestQuandoCaronaNaoTransportaEncomendasDeveRetornar409() {
        UUID rideId = UUID.randomUUID();
        Ride ride = mock(Ride.class);

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        when(ride.getDeletedAt()).thenReturn(null);
        when(ride.getVisibility()).thenReturn(RideVisibility.PUBLIC);
        when(ride.getDriverSub()).thenReturn("motorista-123");
        when(ride.carriesPackages()).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, "remetente-123", "Remetente", null)
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void requestQuandoCaronaNaoEstaAbertaParaReservasDeveRetornar409() {
        UUID rideId = UUID.randomUUID();
        Ride ride = mock(Ride.class);

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        when(ride.getDeletedAt()).thenReturn(null);
        when(ride.getVisibility()).thenReturn(RideVisibility.PUBLIC);
        when(ride.getDriverSub()).thenReturn("motorista-123");
        when(ride.carriesPackages()).thenReturn(true);
        when(ride.openForBookings()).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, "remetente-123", "Remetente", null)
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void requestComCaronaPrivadaDeveRetornar404() {
        UUID rideId = UUID.randomUUID();
        Ride ride = mock(Ride.class);

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        when(ride.getDeletedAt()).thenReturn(null);
        when(ride.getVisibility()).thenReturn(RideVisibility.PRIVATE);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.request(rideId, "remetente", "Remetente", null)
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    // =========================================================
    // LIST FOR DRIVER
    // =========================================================

    @Test
    void listForDriverSemEncomendasDeveRetornarListaVazia() {
        UUID rideId = UUID.randomUUID();
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        when(deliveries.findByRideIdAndDeletedAtIsNull(rideId))
                .thenReturn(List.of());

        List<?> resultado = service.listForDriver(rideId, "motorista-123");

        assertTrue(resultado.isEmpty());
    }

    @Test
    void listForDriverDeOutroMotoristaDeveRetornar403() {
        UUID rideId = UUID.randomUUID();
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.listForDriver(rideId, "outro-motorista")
        );

        assertEquals(403, exception.getStatusCode().value());
    }

    // =========================================================
    // DECIDE
    // =========================================================

    @Test
    void decideComCaronaInexistenteDeveRetornar404() {
        UUID rideId = UUID.randomUUID();

        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(
                        rideId, UUID.randomUUID(), "motorista-123", true, null
                )
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void decidePorOutroMotoristaDeveRetornar403() {
        UUID rideId = UUID.randomUUID();
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(
                        rideId, UUID.randomUUID(), "outro-motorista", true, null
                )
        );

        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void decideComViagemIniciadaDeveRetornar409() {
        UUID rideId = UUID.randomUUID();
        Ride ride = prepararMotorista(rideId, "motorista-123");
        when(ride.getStatus()).thenReturn(RideStatus.IN_PROGRESS);

        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(
                        rideId, UUID.randomUUID(), "motorista-123", true, null
                )
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void decideComEncomendaInexistenteDeveRetornar404() {
        UUID rideId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(ride.getStatus()).thenReturn(RideStatus.PLANNED);
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));
        when(deliveries.findById(deliveryId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(
                        rideId, deliveryId, "motorista-123", true, null
                )
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void decideComEncomendaDeOutraCaronaDeveRetornar404() {
        UUID rideId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID outraRide = UUID.randomUUID();

        Ride ride = prepararMotorista(rideId, "motorista-123");
        Delivery delivery = mock(Delivery.class);

        when(ride.getStatus()).thenReturn(RideStatus.PLANNED);
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(outraRide);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(
                        rideId, deliveryId, "motorista-123", true, null
                )
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void decideComPedidoJaDecididoDeveRetornar409() {
        UUID rideId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        Ride ride = prepararMotorista(rideId, "motorista-123");
        Delivery delivery = mock(Delivery.class);

        when(ride.getStatus()).thenReturn(RideStatus.PLANNED);
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.CONFIRMED);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(
                        rideId, deliveryId, "motorista-123", true, null
                )
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void decideRecusaSemMotivoDeveRetornar400() {
        UUID rideId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        Ride ride = prepararMotorista(rideId, "motorista-123");
        Delivery delivery = mock(Delivery.class);

        when(ride.getStatus()).thenReturn(RideStatus.PLANNED);
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.PENDING);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(
                        rideId, deliveryId, "motorista-123", false, " "
                )
        );

        assertEquals(400, exception.getStatusCode().value());
    }

    // =========================================================
    // PICK UP
    // =========================================================

    @Test
    void pickUpComEncomendaInexistenteDeveRetornar404() {
        UUID deliveryId = UUID.randomUUID();

        when(deliveries.findById(deliveryId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.pickUp(deliveryId, "motorista-123", "123456")
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void pickUpPorOutroMotoristaDeveRetornar403() {
        UUID deliveryId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();

        Delivery delivery = mock(Delivery.class);
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.pickUp(deliveryId, "outro-motorista", "123456")
        );

        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void pickUpComStatusInvalidoDeveRetornar409() {
        UUID deliveryId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();

        Delivery delivery = mock(Delivery.class);
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.PENDING);
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.pickUp(deliveryId, "motorista-123", "123456")
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    // =========================================================
    // DELIVER
    // =========================================================

    @Test
    void deliverComEncomendaInexistenteDeveRetornar404() {
        UUID deliveryId = UUID.randomUUID();

        when(deliveries.findById(deliveryId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.deliver(deliveryId, "motorista-123", "PALAVRA")
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void deliverComStatusInvalidoDeveRetornar409() {
        UUID deliveryId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();

        Delivery delivery = mock(Delivery.class);
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.CONFIRMED);
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.deliver(deliveryId, "motorista-123", "PALAVRA")
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    // =========================================================
    // CANCEL
    // =========================================================

    @Test
    void cancelComEncomendaInexistenteDeveRetornar404() {
        UUID deliveryId = UUID.randomUUID();

        when(deliveries.findById(deliveryId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(deliveryId, "remetente-123", null)
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void cancelPorTerceiroDeveRetornar404() {
        UUID deliveryId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();

        Delivery delivery = mock(Delivery.class);
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getSenderSub()).thenReturn("remetente-123");
        when(delivery.getCompanyId()).thenReturn(null);
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(deliveryId, "terceiro-456", null)
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void cancelDepoisDaColetaDeveRetornar409() {
        UUID deliveryId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();

        Delivery delivery = mock(Delivery.class);
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getSenderSub()).thenReturn("remetente-123");
        when(delivery.getStatus()).thenReturn(DeliveryStatus.IN_TRANSIT);
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.cancel(deliveryId, "remetente-123", null)
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    // =========================================================
    // GET
    // =========================================================

    @Test
    void getComEncomendaInexistenteDeveRetornar404() {
        UUID deliveryId = UUID.randomUUID();

        when(deliveries.findById(deliveryId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.get(deliveryId, "usuario-123")
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void getPorTerceiroDeveRetornar404() {
        UUID deliveryId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();

        Delivery delivery = mock(Delivery.class);
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getSenderSub()).thenReturn("remetente-123");
        when(delivery.getCompanyId()).thenReturn(null);
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.get(deliveryId, "terceiro-456")
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    // =========================================================
    // MINE
    // =========================================================

    @Test
    void mineSemEncomendasDeveRetornarListaVazia() {
        String senderSub = "remetente-123";

        when(membership.companyIdsOf(senderSub)).thenReturn(List.of());
        when(deliveries.findMine(eq(senderSub), anyList()))
                .thenReturn(List.of());

        List<?> resultado = service.mine(senderSub);

        assertTrue(resultado.isEmpty());
    }

    // =========================================================
    // PODE VER COMO REMETENTE
    // =========================================================

    @Test
    void podeVerComoRemetenteQuandoSubCorrespondeDeveRetornarTrue() {
        Delivery delivery = mock(Delivery.class);

        when(delivery.getSenderSub()).thenReturn("remetente-123");

        assertTrue(service.podeVerComoRemetente(delivery, "remetente-123"));
    }

    @Test
    void podeVerComoRemetenteQuandoNaoCorrespondeDeveRetornarFalse() {
        Delivery delivery = mock(Delivery.class);

        when(delivery.getSenderSub()).thenReturn("remetente-123");
        when(delivery.getCompanyId()).thenReturn(null);

        assertFalse(service.podeVerComoRemetente(delivery, "outro-456"));
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private Ride prepararMotorista(UUID rideId, String driverSub) {
        Ride ride = mock(Ride.class);

        when(ride.getDriverSub()).thenReturn(driverSub);

        return ride;
    }
}
