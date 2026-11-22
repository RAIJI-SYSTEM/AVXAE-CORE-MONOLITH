package com.raiji.deliveries.service;

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
import com.raiji.deliveries.DeliveryStatusHistory;
import com.raiji.deliveries.event.DeliveryDeliveredEvent;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import com.raiji.common.enums.RideType;
import com.raiji.deliveries.dto.AvailableDeliveryResponse;
import org.springframework.http.HttpStatus;
import com.raiji.users.repository.VehicleRepository;
import org.springframework.context.ApplicationEventPublisher;
import com.raiji.deliveries.dto.DeliveryConfirmationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.raiji.deliveries.dto.DeliveryRequest;
import com.raiji.deliveries.dto.DeliverySummaryResponse;
import org.mockito.ArgumentCaptor;
import com.raiji.deliveries.DeliveryMode;

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

    @Mock
    private TrackingCodeGenerator trackingCodeGenerator;

    @Mock
    private VehicleRepository vehicles;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private RecipientNotificationSimulator recipientNotificationSimulator;

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

        when(ride.getStatus()).thenReturn(RideStatus.PLANNED);

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
    // CONFIRMAR ENTREGA
    // =========================================================

    @Test
    void confirmComEncomendaInexistenteDeveRetornar404() {
        UUID deliveryId = UUID.randomUUID();

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.empty());

        DeliveryConfirmationRequest body =
                new DeliveryConfirmationRequest(
                        "123456",
                        "Maria Souza",
                        null
                );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.confirm(
                        deliveryId,
                        "motorista-123",
                        body
                )
        );

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void confirmComStatusInvalidoDeveRetornar409() {
        UUID deliveryId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();

        Delivery delivery = mock(Delivery.class);
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.of(delivery));

        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getMode()).thenReturn(DeliveryMode.ON_ROUTE);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.CONFIRMED);

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        DeliveryConfirmationRequest body =
                new DeliveryConfirmationRequest(
                        "123456",
                        "Maria Souza",
                        null
                );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.confirm(
                        deliveryId,
                        "motorista-123",
                        body
                )
        );

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void confirmComCodigoIncorretoDeveRegistrarTentativa() {
        UUID deliveryId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();

        Delivery delivery = mock(Delivery.class);
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.of(delivery));

        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getMode()).thenReturn(DeliveryMode.ON_ROUTE);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.IN_TRANSIT);
        when(delivery.getConfirmationAttempts()).thenReturn(0);
        when(delivery.getConfirmationCode()).thenReturn("123456");

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        DeliveryConfirmationRequest body =
                new DeliveryConfirmationRequest(
                        "999999",
                        "Maria Souza",
                        null
                );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.confirm(
                        deliveryId,
                        "motorista-123",
                        body
                )
        );

        assertEquals(400, exception.getStatusCode().value());

        verify(delivery).registerConfirmationAttempt();
        verify(deliveries).saveAndFlush(delivery);
    }

    @Test
    void confirmComCincoTentativasDeveRetornar423() {
        UUID deliveryId = UUID.randomUUID();
        UUID rideId = UUID.randomUUID();

        Delivery delivery = mock(Delivery.class);
        Ride ride = prepararMotorista(rideId, "motorista-123");

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.of(delivery));

        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getRideId()).thenReturn(rideId);
        when(delivery.getMode()).thenReturn(DeliveryMode.ON_ROUTE);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.IN_TRANSIT);
        when(delivery.getConfirmationAttempts()).thenReturn(5);

        when(rides.findById(rideId)).thenReturn(Optional.of(ride));

        DeliveryConfirmationRequest body =
                new DeliveryConfirmationRequest(
                        "123456",
                        "Maria Souza",
                        null
                );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.confirm(
                        deliveryId,
                        "motorista-123",
                        body
                )
        );

        assertEquals(423, exception.getStatusCode().value());

        verify(delivery, never()).registerConfirmationAttempt();
        verify(deliveries, never()).saveAndFlush(delivery);
    }
    @Test
    void confirmComCodigoValidoDeveEntregarEncomendaERegistrarEventos() {
        UUID deliveryId = UUID.randomUUID();
        String driverSub = "motorista-123";
        Instant deliveredAt = Instant.parse("2026-11-22T12:00:00Z");

        // Cria uma entrega real para testar suas transições de estado.
        Delivery delivery = new Delivery();

        ReflectionTestUtils.setField(delivery, "id", deliveryId);

        PackageDetails pkg = delivery.getPkg();

        pkg.setSize(PackageSize.SMALL);
        pkg.setDescription("Documentos");
        pkg.setWeightKg(new BigDecimal("2.00"));
        pkg.setLengthCm(20);
        pkg.setWidthCm(15);
        pkg.setHeightCm(10);
        pkg.setFragile(false);
        pkg.setDeclaredValue(new BigDecimal("50.00"));
        pkg.setRecipientName("Maria da Silva");
        pkg.setRecipientPhone("84999999999");

        delivery.setDriverSub(driverSub);
        delivery.setMode(DeliveryMode.ON_ROUTE);
        delivery.setConfirmationCode("123456");
        delivery.setPrice(new BigDecimal("100.00"));
        delivery.setPlatformFeePercent(new BigDecimal("10.00"));

        // Coloca a entrega no estado necessário para a confirmação.
        delivery.confirm();
        delivery.startTransit(deliveredAt);

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.of(delivery));

        when(clock.instant()).thenReturn(deliveredAt);

        when(deliveries.save(any(Delivery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeliveryConfirmationRequest body = new DeliveryConfirmationRequest(
                "123456",
                "Maria da Silva",
                "Entregue em mãos ao destinatário."
        );

        // Executa a confirmação.
        var response = service.confirm(deliveryId, driverSub, body);

        // Verifica os dados da entrega.
        assertEquals(DeliveryStatus.DELIVERED, delivery.getStatus());
        assertEquals(deliveredAt, delivery.getDeliveredAt());
        assertEquals(9000L, delivery.getRewardCents());
        assertEquals("Maria da Silva", delivery.getConfirmedRecipientName());
        assertEquals(
                "Entregue em mãos ao destinatário.",
                delivery.getDeliveryObservation()
        );

        // Verifica a resposta da API.
        assertEquals(DeliveryStatus.DELIVERED, response.status());
        assertEquals(9000L, response.rewardCents());
        assertEquals("Maria da Silva", response.confirmedRecipientName());

        // Verifica o histórico registrado.
        ArgumentCaptor<DeliveryStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(DeliveryStatusHistory.class);

        verify(history).save(historyCaptor.capture());

        DeliveryStatusHistory savedHistory = historyCaptor.getValue();

        assertEquals(deliveryId, savedHistory.getDeliveryId());
        assertEquals(DeliveryStatus.IN_TRANSIT, savedHistory.getFromStatus());
        assertEquals(DeliveryStatus.DELIVERED, savedHistory.getToStatus());
        assertEquals(driverSub, savedHistory.getChangedBy());

        // Verifica a publicação do evento de entrega concluída.
        ArgumentCaptor<DeliveryDeliveredEvent> eventCaptor =
                ArgumentCaptor.forClass(DeliveryDeliveredEvent.class);

        verify(events).publishEvent(eventCaptor.capture());

        DeliveryDeliveredEvent event = eventCaptor.getValue();

        assertEquals(deliveryId, event.deliveryId());
        assertEquals(driverSub, event.driverSub());
        assertEquals(9000L, event.rewardCents());
        assertEquals(deliveredAt, event.deliveredAt());

        // Verifica a notificação ao remetente.
        verify(notifier).sender(
                eq(delivery),
                eq("DELIVERY_DELIVERED"),
                contains("Maria da Silva")
        );

        // Verifica a persistência.
        verify(deliveries).save(delivery);
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
// CRIAÇÃO DE ENTREGA INDEPENDENTE
// =========================================================

    @Test
    void createComDadosValidosDeveCriarEntregaPendente() {
        String senderSub = "remetente-123";
        String tokenName = "Alan";

        Instant agora = Instant.parse("2026-11-18T12:00:00Z");

        DeliveryRequest request = new DeliveryRequest(
                DeliveryMode.ON_ROUTE,
                "Fortaleza",
                "CE",
                "Terminal Rodoviário, box 12",
                "Sobral",
                "CE",
                "Rua das Flores, 100",
                "Maria Souza",
                "+5585999990000",
                "Caixa com livros",
                new BigDecimal("4.50"),
                40,
                30,
                25
        );

        when(clock.instant()).thenReturn(agora);
        when(trackingCodeGenerator.generate()).thenReturn("RM-ABC234-56");

        when(deliveries.save(any(Delivery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeliverySummaryResponse response =
                service.create(senderSub, tokenName, request);

        ArgumentCaptor<Delivery> captor =
                ArgumentCaptor.forClass(Delivery.class);

        verify(deliveries).save(captor.capture());

        Delivery saved = captor.getValue();

        assertNotNull(response);
        assertEquals(DeliveryStatus.PENDING, response.status());
        assertEquals("RM-ABC234-56", response.trackingCode());

        assertEquals(senderSub, saved.getSenderSub());
        assertEquals("RM-ABC234-56", saved.getTrackingCode());
        assertNull(saved.getRideId());

        assertEquals("Fortaleza", saved.getOriginCity());
        assertEquals("Sobral", saved.getDestinationCity());

        assertEquals(DeliveryStatus.PENDING, saved.getStatus());
        assertEquals(agora, saved.getRequestedAt());

        assertEquals("Caixa com livros", saved.getPkg().getDescription());
        assertEquals(new BigDecimal("4.50"), saved.getPkg().getWeightKg());
        assertEquals(40, saved.getPkg().getLengthCm());
        assertEquals(30, saved.getPkg().getWidthCm());
        assertEquals(25, saved.getPkg().getHeightCm());

        verify(trackingCodeGenerator).generate();
    }

    // =========================================================
    // AVAILABLE - ENTREGAS INDEPENDENTES COMPATIVEIS
    // =========================================================

    @Test
    void availableComCaronaCompativelDeveRetornarEntrega() {
        String driverSub = "motorista-123";
        UUID rideId = UUID.randomUUID();

        LocalDateTime partida = LocalDateTime.of(2026, 12, 10, 10, 0);
        Instant agora = Instant.parse("2026-11-18T12:00:00Z");

        Ride ride = mock(Ride.class);
        Delivery delivery = criarEntregaIndependente();

        // Configuração do relógio
        when(clock.instant()).thenReturn(agora);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        // Configuração da carona
        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                eq(driverSub), eq(List.of(RideStatus.PLANNED))
        )).thenReturn(List.of(ride));

        when(ride.openForBookings()).thenReturn(true);
        when(ride.getType()).thenReturn(RideType.CARGO);
        when(ride.getDepartureDate()).thenReturn(partida);
        when(ride.getOriginCity()).thenReturn("Fortaleza");
        when(ride.getOriginState()).thenReturn("CE");
        when(ride.getDestinationCity()).thenReturn("Sobral");
        when(ride.getDestinationState()).thenReturn("CE");
        when(ride.getId()).thenReturn(rideId);

        // Configuração do pacote
        PackageDetails pkg = prepararPacoteParaCapacidade(delivery);

        when(pkg.getDescription()).thenReturn("Caixa de livros");
        when(pkg.getRecipientName()).thenReturn("Maria Souza");

        // Dados necessários para a resposta
        when(delivery.getId()).thenReturn(UUID.randomUUID());
        when(delivery.getTrackingCode()).thenReturn("RM-ABC234-56");
        when(delivery.getStatus()).thenReturn(DeliveryStatus.PENDING);
        when(delivery.getMode()).thenReturn(DeliveryMode.ON_ROUTE);
        when(delivery.getOriginAddress()).thenReturn("Rua A");
        when(delivery.getDestinationAddress()).thenReturn("Rua B");

        // Entrega disponível
        when(deliveries
                .findByStatusAndRideIdIsNullAndDeletedAtIsNullOrderByRequestedAtDesc(
                        DeliveryStatus.PENDING
                )).thenReturn(List.of(delivery));

        // Execução
        List<AvailableDeliveryResponse> resultado = service.available(driverSub);

        // Verificações
        assertEquals(1, resultado.size());
        assertEquals("RM-ABC234-56", resultado.get(0).trackingCode());
        assertEquals(1, resultado.get(0).compatibleRides().size());
        assertEquals(rideId, resultado.get(0).compatibleRides().get(0).id());

        verify(capacity).assertItemDimensions(ride, 40, 30, 25);
        verify(capacity).assertFits(eq(ride), any(), isNull());
    }

    @Test
    void availableComCidadeDeOrigemDiferenteDeveRetornarListaVazia() {
        String driverSub = "motorista-123";

        Ride ride = mock(Ride.class);
        Delivery delivery = mock(Delivery.class);

        // Relógio
        when(clock.instant())
                .thenReturn(Instant.parse("2026-11-18T12:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        // Carona
        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                eq(driverSub), eq(List.of(RideStatus.PLANNED))
        )).thenReturn(List.of(ride));

        when(ride.openForBookings()).thenReturn(true);
        when(ride.getType()).thenReturn(RideType.CARGO);
        when(ride.getDepartureDate())
                .thenReturn(LocalDateTime.of(2026, 12, 10, 10, 0));
        when(ride.getOriginCity()).thenReturn("Natal");

        // Entrega: somente a cidade de origem é consultada
        when(delivery.getOriginCity()).thenReturn("Fortaleza");

        when(deliveries
                .findByStatusAndRideIdIsNullAndDeletedAtIsNullOrderByRequestedAtDesc(
                        DeliveryStatus.PENDING
                )).thenReturn(List.of(delivery));

        // Execução
        List<AvailableDeliveryResponse> resultado = service.available(driverSub);

        // Verificações
        assertTrue(resultado.isEmpty());
        verifyNoInteractions(capacity);
    }

    @Test
    void availableComUfDeDestinoDiferenteDeveRetornarListaVazia() {
        String driverSub = "motorista-123";

        Ride ride = prepararCaronaDisponivel(
                "Fortaleza", "CE", "Sobral", "RN"
        );

        Delivery delivery = criarEntregaIndependente();

        prepararEntregasDisponiveis(driverSub, ride, delivery);

        List<AvailableDeliveryResponse> resultado = service.available(driverSub);

        assertTrue(resultado.isEmpty());
        verifyNoInteractions(capacity);
    }

    @Test
    void availableComCapacidadeInsuficienteDeveRetornarListaVazia() {
        String driverSub = "motorista-123";

        Ride ride = prepararCaronaDisponivel(
                "Fortaleza", "CE", "Sobral", "CE"
        );

        Delivery delivery = criarEntregaIndependente();
        prepararPacoteParaCapacidade(delivery);

        prepararEntregasDisponiveis(driverSub, ride, delivery);

        doThrow(new ResponseStatusException(
                HttpStatus.CONFLICT, "Capacidade insuficiente"
        )).when(capacity).assertFits(eq(ride), any(), isNull());

        List<AvailableDeliveryResponse> resultado = service.available(driverSub);

        assertTrue(resultado.isEmpty());

        verify(capacity).assertItemDimensions(ride, 40, 30, 25);
        verify(capacity).assertFits(eq(ride), any(), isNull());
    }

    @Test
    void availableSemCaronasDoMotoristaDeveRetornarListaVazia() {
        String driverSub = "motorista-123";

        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                eq(driverSub), eq(List.of(RideStatus.PLANNED))
        )).thenReturn(List.of());

        when(clock.instant()).thenReturn(Instant.parse("2026-11-18T12:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        List<AvailableDeliveryResponse> resultado = service.available(driverSub);

        assertTrue(resultado.isEmpty());

        verify(deliveries, never())
                .findByStatusAndRideIdIsNullAndDeletedAtIsNullOrderByRequestedAtDesc(
                        any()
                );
    }

    @Test
    void availableComCaronaFechadaDeveRetornarListaVazia() {
        String driverSub = "motorista-123";

        Ride ride = mock(Ride.class);
        when(ride.openForBookings()).thenReturn(false);

        when(clock.instant()).thenReturn(Instant.parse("2026-11-18T12:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                eq(driverSub), eq(List.of(RideStatus.PLANNED))
        )).thenReturn(List.of(ride));

        List<AvailableDeliveryResponse> resultado = service.available(driverSub);

        assertTrue(resultado.isEmpty());
        verify(deliveries, never())
                .findByStatusAndRideIdIsNullAndDeletedAtIsNullOrderByRequestedAtDesc(
                        any()
                );
    }

    // =========================================================
    // RESEND CONFIRMATION CODE
    // =========================================================

    @Test
    void resendCodeDeveReenviarMesmoCodigoAoDestinatario() {
        UUID deliveryId = UUID.randomUUID();
        String senderSub = "remetente-123";
        String confirmationCode = "123456";
        String phone = "+5585999990000";

        Delivery delivery = mock(Delivery.class);
        PackageDetails pkg = mock(PackageDetails.class);

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getSenderSub()).thenReturn(senderSub);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.PENDING);
        when(delivery.getPkg()).thenReturn(pkg);
        when(delivery.getId()).thenReturn(deliveryId);
        when(delivery.getConfirmationCode()).thenReturn(confirmationCode);
        when(pkg.getRecipientPhone()).thenReturn(phone);

        assertDoesNotThrow(() -> service.resendCode(deliveryId, senderSub));

        verify(recipientNotificationSimulator).sendConfirmationCode(
                deliveryId,
                phone,
                confirmationCode
        );

        verify(notifier).sender(
                delivery,
                "DELIVERY_CODE_RESENT",
                "O código de confirmação da sua entrega foi reenviado ao destinatário."
        );
    }

    @Test
    void resendCodeDeveNegarAcessoDeOutroUsuario() {
        UUID deliveryId = UUID.randomUUID();
        String senderSub = "remetente-123";

        Delivery delivery = mock(Delivery.class);

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getSenderSub()).thenReturn(senderSub);
        when(delivery.getCompanyId()).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.resendCode(deliveryId, "outro-usuario")
        );

        assertEquals(404, exception.getStatusCode().value());

        verifyNoInteractions(recipientNotificationSimulator, notifier);
    }

    @Test
    void resendCodeDeveBloquearEntregaConcluida() {
        UUID deliveryId = UUID.randomUUID();
        String senderSub = "remetente-123";

        Delivery delivery = mock(Delivery.class);

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getSenderSub()).thenReturn(senderSub);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.DELIVERED);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.resendCode(deliveryId, senderSub)
        );

        assertEquals(409, exception.getStatusCode().value());

        verifyNoInteractions(recipientNotificationSimulator, notifier);
    }

    @Test
    void resendCodeDeveBloquearEntregaCancelada() {
        UUID deliveryId = UUID.randomUUID();
        String senderSub = "remetente-123";

        Delivery delivery = mock(Delivery.class);

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getSenderSub()).thenReturn(senderSub);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.CANCELLED);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.resendCode(deliveryId, senderSub)
        );

        assertEquals(409, exception.getStatusCode().value());

        verifyNoInteractions(recipientNotificationSimulator, notifier);
    }

    @Test
    void resendCodeSemTelefoneDoDestinatarioDeveRetornar409() {
        UUID deliveryId = UUID.randomUUID();
        String senderSub = "remetente-123";

        Delivery delivery = mock(Delivery.class);
        PackageDetails pkg = mock(PackageDetails.class);

        when(deliveries.findByIdForUpdate(deliveryId))
                .thenReturn(Optional.of(delivery));
        when(delivery.getDeletedAt()).thenReturn(null);
        when(delivery.getSenderSub()).thenReturn(senderSub);
        when(delivery.getStatus()).thenReturn(DeliveryStatus.PENDING);
        when(delivery.getPkg()).thenReturn(pkg);
        when(pkg.getRecipientPhone()).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.resendCode(deliveryId, senderSub)
        );

        assertEquals(409, exception.getStatusCode().value());

        verifyNoInteractions(recipientNotificationSimulator, notifier);
    }


    // =========================================================
    // HELPERS - AVAILABLE
    // =========================================================

    private Delivery criarEntregaIndependente() {
        Delivery delivery = mock(Delivery.class);

        when(delivery.getOriginCity()).thenReturn("Fortaleza");
        when(delivery.getOriginState()).thenReturn("CE");
        when(delivery.getDestinationCity()).thenReturn("Sobral");
        when(delivery.getDestinationState()).thenReturn("CE");

        return delivery;
    }

    private Ride prepararCaronaDisponivel(
            String originCity,
            String originState,
            String destinationCity,
            String destinationState
    ) {
        Ride ride = mock(Ride.class);

        when(ride.openForBookings()).thenReturn(true);
        when(ride.getType()).thenReturn(RideType.CARGO);
        when(ride.getDepartureDate())
                .thenReturn(LocalDateTime.of(2026, 12, 10, 10, 0));
        when(ride.getOriginCity()).thenReturn(originCity);
        when(ride.getOriginState()).thenReturn(originState);
        when(ride.getDestinationCity()).thenReturn(destinationCity);
        when(ride.getDestinationState()).thenReturn(destinationState);

        return ride;
    }

    private void prepararEntregasDisponiveis(
            String driverSub,
            Ride ride,
            Delivery delivery
    ) {
        when(clock.instant()).thenReturn(
                Instant.parse("2026-11-18T12:00:00Z")
        );
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
                eq(driverSub), eq(List.of(RideStatus.PLANNED))
        )).thenReturn(List.of(ride));

        when(deliveries
                .findByStatusAndRideIdIsNullAndDeletedAtIsNullOrderByRequestedAtDesc(
                        DeliveryStatus.PENDING
                )).thenReturn(List.of(delivery));
    }


    // =========================================================
    // HELPERS
    // =========================================================

    private Ride prepararMotorista(UUID rideId, String driverSub) {
        Ride ride = mock(Ride.class);

        when(ride.getDriverSub()).thenReturn(driverSub);

        return ride;
    }
    private PackageDetails prepararPacoteParaCapacidade(Delivery delivery) {
        PackageDetails pkg = mock(PackageDetails.class);

        when(delivery.getPkg()).thenReturn(pkg);

        when(pkg.getWeightKg()).thenReturn(new BigDecimal("4.50"));
        when(pkg.getLengthCm()).thenReturn(40);
        when(pkg.getWidthCm()).thenReturn(30);
        when(pkg.getHeightCm()).thenReturn(25);

        return pkg;
    }
    @Test
    void createDeveNormalizarEstadosParaMaiusculas() {
        DeliveryRequest request = new DeliveryRequest(
                DeliveryMode.ON_ROUTE,
                "Fortaleza",
                "ce",
                "Rua A",
                "Sobral",
                "rn",
                "Rua B",
                "Maria Souza",
                "+5585999990000",
                "Caixa com livros",
                new BigDecimal("4.50"),
                40,
                30,
                25
        );

        when(clock.instant()).thenReturn(
                Instant.parse("2026-11-18T12:00:00Z"));

        when(trackingCodeGenerator.generate())
                .thenReturn("RM-ABC234-56");

        when(deliveries.save(any(Delivery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create("remetente-123", "Alan", request);

        ArgumentCaptor<Delivery> captor =
                ArgumentCaptor.forClass(Delivery.class);

        verify(deliveries).save(captor.capture());

        Delivery saved = captor.getValue();

        assertEquals("CE", saved.getOriginState());
        assertEquals("RN", saved.getDestinationState());
    }
    @Test
    void createComOrigemIgualAoDestinoDeveRetornar400() {
        DeliveryRequest request = new DeliveryRequest(
                DeliveryMode.ON_ROUTE,
                "Fortaleza",
                "ce",
                "Rua A, 100",
                "FORTALEZA",
                "CE",
                "Rua A, 100",
                "Maria Souza",
                "+5585999990000",
                "Caixa com livros",
                new BigDecimal("4.50"),
                40,
                30,
                25
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.create("remetente-123", "Alan", request)
        );

        assertEquals(400, exception.getStatusCode().value());

        verifyNoInteractions(trackingCodeGenerator);
        verify(deliveries, never()).save(any(Delivery.class));
    }

    @Test
    void driverHistoryDeveConsultarRepositorioComFiltros() {
        String driverSub = "motorista-123";
        Instant inicio = Instant.parse("2026-11-01T00:00:00Z");
        Instant fim = Instant.parse("2026-11-30T23:59:59Z");
        Pageable pageable = Pageable.unpaged();

        when(deliveries.findDriverHistory(
                driverSub,
                DeliveryStatus.DELIVERED,
                inicio,
                fim,
                pageable
        )).thenReturn(Page.empty());

        Page<DeliverySummaryResponse> resultado = service.driverHistory(
                driverSub,
                DeliveryStatus.DELIVERED,
                inicio,
                fim,
                pageable
        );

        assertTrue(resultado.isEmpty());

        verify(deliveries).findDriverHistory(
                driverSub,
                DeliveryStatus.DELIVERED,
                inicio,
                fim,
                pageable
        );
    }

    @Test
    void senderHistoryDeveIncluirEmpresasDoRemetente() {
        String senderSub = "remetente-123";
        UUID companyId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();

        when(membership.companyIdsOf(senderSub))
                .thenReturn(List.of(companyId));

        when(deliveries.findSenderHistory(
                senderSub,
                List.of(companyId),
                null,
                null,
                null,
                pageable
        )).thenReturn(Page.empty());

        Page<DeliverySummaryResponse> resultado = service.senderHistory(
                senderSub,
                null,
                null,
                null,
                pageable
        );

        assertTrue(resultado.isEmpty());

        verify(deliveries).findSenderHistory(
                senderSub,
                List.of(companyId),
                null,
                null,
                null,
                pageable
        );
    }

    @Test
    void historyDeveRejeitarPeriodoInvalido() {
        Instant inicio = Instant.parse("2026-11-30T00:00:00Z");
        Instant fim = Instant.parse("2026-11-01T00:00:00Z");

        assertThrows(ResponseStatusException.class, () ->
                service.driverHistory(
                        "motorista-123",
                        null,
                        inicio,
                        fim,
                        Pageable.unpaged()
                )
        );

        verifyNoInteractions(deliveries);
    }

}
