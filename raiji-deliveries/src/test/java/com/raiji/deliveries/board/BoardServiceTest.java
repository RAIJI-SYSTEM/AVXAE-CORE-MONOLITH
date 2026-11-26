package com.raiji.deliveries.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.domain.PackageSize;
import com.raiji.deliveries.freight.FreightDtos.FreightPlace;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.deliveries.service.DeliveryNotifier;
import com.raiji.rides.cargo.CargoCapacityService;
import com.raiji.rides.config.RidePricingProperties;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.repository.RideRouteMatch;
import com.raiji.rides.service.RidePassengerService;
import com.raiji.rides.service.RideService;
import com.raiji.users.company.CompanyMembership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.raiji.deliveries.support.Fixtures;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BoardServiceTest {

    @Mock BoardRequestRepository requests;
    @Mock BoardOfferRepository offers;
    @Mock DeliveryRepository deliveries;
    @Mock RideRepository rides;
    @Mock RideService rideService;
    @Mock RidePassengerService passengers;
    @Mock CargoCapacityService capacity;
    @Mock CompanyMembership membership;
    @Mock DeliveryNotifier notifier;
    @Mock RidePricingProperties pricing;

    BoardService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

    @BeforeEach
    void setUp() {
        service = new BoardService(requests, offers, deliveries, rides, rideService, passengers, capacity,
                membership, notifier, pricing, clock);
        when(offers.findByRequestIdInAndDeletedAtIsNullOrderByPriceAsc(any())).thenReturn(List.of());
        when(offers.findByRequestIdAndStatus(any(), any())).thenReturn(List.of());
        when(rides.findAllById(any())).thenReturn(List.of());
        when(rideService.describeVehicles(any())).thenReturn(new java.util.HashMap<>());
        when(pricing.platformFeePercent()).thenReturn(new BigDecimal("10"));
    }

    private LocalDateTime soon() {
        return LocalDateTime.now(clock).plusHours(3);
    }

    private BoardRequest board(BoardKind kind) {
        BoardRequest b = new BoardRequest();
        ReflectionTestUtils.setField(b, "id", requestId);
        b.setKind(kind);
        b.setRequesterSub("owner");
        b.setRequesterName("Ana Souza");
        b.setOriginCity("São Paulo");
        b.setOriginState("SP");
        b.setOriginLocation(GeoSupport.point(-23.55, -46.63));
        b.setOriginAddress("Rua A");
        b.setDestinationCity("Campinas");
        b.setDestinationState("SP");
        b.setDestinationLocation(GeoSupport.point(-22.90, -47.06));
        b.setDestinationAddress("Rua B");
        b.setDesiredAt(soon());
        b.setPkg(kind == BoardKind.PACKAGE ? Fixtures.pkg() : null);
        return b;
    }

    private FreightPlace place(String city, String state) {
        return new FreightPlace(city, state, -23.55, -46.63, "Rua X");
    }

    private BoardDtos.CreateBoardRequest createBody(BoardKind kind, UUID companyId) {
        return new BoardDtos.CreateBoardRequest(kind, place("São Paulo", "sp"), place("Campinas", "sp"), soon(),
                kind == BoardKind.PACKAGE ? Fixtures.pkgRequest() : null, companyId, "  observação  ");
    }

    // ------------------------------------------------------------------ publicar

    @Test
    void publicaPedidoDeCarona() {
        when(requests.save(any())).thenAnswer(inv -> { BoardRequest saved = inv.getArgument(0); ReflectionTestUtils.setField(saved, "id", requestId); return saved; });

        BoardDtos.BoardResponse r = service.publish("owner", "Ana Souza", createBody(BoardKind.SEAT, null));

        assertThat(r.kind()).isEqualTo(BoardKind.SEAT);
        assertThat(r.status()).isEqualTo(BoardStatus.OPEN);
        assertThat(r.mine()).isTrue();
        assertThat(r.requesterName()).isEqualTo("Ana Souza");
        assertThat(r.originCity()).isEqualTo("São Paulo");
        assertThat(r.originState()).isEqualTo("SP");
        assertThat(r.originLatitude()).isEqualTo(-23.55);
        assertThat(r.notes()).isEqualTo("observação");
        assertThat(r.size()).isNull();
    }

    @Test
    void publicaEncomendaComDadosDoPacote() {
        when(requests.save(any())).thenAnswer(inv -> { BoardRequest saved = inv.getArgument(0); ReflectionTestUtils.setField(saved, "id", requestId); return saved; });
        BoardDtos.BoardResponse r = service.publish("owner", "Ana", createBody(BoardKind.PACKAGE, null));

        assertThat(r.size()).isEqualTo(PackageSize.MEDIUM);
        assertThat(r.description()).isEqualTo("Caixa");
        assertThat(r.weightKg()).isEqualByComparingTo("2.00");
        assertThat(r.recipientName()).isEqualTo("Bia");
        assertThat(r.recipientPhone()).isEqualTo("+5511999999999");
    }

    @Test
    void nomeEmBrancoViraUsuario() {
        when(requests.save(any())).thenAnswer(inv -> { BoardRequest saved = inv.getArgument(0); ReflectionTestUtils.setField(saved, "id", requestId); return saved; });
        assertThat(service.publish("owner", "  ", createBody(BoardKind.SEAT, null)).requesterName()).isEqualTo("Usuário");
        assertThat(service.publish("owner", null, createBody(BoardKind.SEAT, null)).requesterName()).isEqualTo("Usuário");
    }

    @Test
    void empresaPrecisaDeMembroAtivo() {
        when(membership.requireActiveMember(any(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        UUID company = UUID.randomUUID();
        assertStatus(() -> service.publish("owner", "Ana", createBody(BoardKind.PACKAGE, company)), HttpStatus.FORBIDDEN);
    }

    @Test
    void rejeitaPedidoMuitoProximoOuMuitoDistante() {
        LocalDateTime agora = LocalDateTime.now(clock);
        BoardDtos.CreateBoardRequest cedo = new BoardDtos.CreateBoardRequest(BoardKind.SEAT, place("A", "SP"),
                place("B", "SP"), agora.plusMinutes(10), null, null, null);
        assertStatus(() -> service.publish("owner", "Ana", cedo), HttpStatus.BAD_REQUEST);

        BoardDtos.CreateBoardRequest longe = new BoardDtos.CreateBoardRequest(BoardKind.SEAT, place("A", "SP"),
                place("B", "SP"), agora.plusMonths(4), null, null, null);
        assertStatus(() -> service.publish("owner", "Ana", longe), HttpStatus.BAD_REQUEST);
    }

    @Test
    void validaPacoteEEmpresaPorTipo() {
        BoardDtos.CreateBoardRequest semPacote = new BoardDtos.CreateBoardRequest(BoardKind.PACKAGE, place("A", "SP"),
                place("B", "SP"), soon(), null, null, null);
        assertStatus(() -> service.publish("owner", "Ana", semPacote), HttpStatus.BAD_REQUEST);

        BoardDtos.CreateBoardRequest caronaComPacote = new BoardDtos.CreateBoardRequest(BoardKind.SEAT, place("A", "SP"),
                place("B", "SP"), soon(), Fixtures.pkgRequest(), null, null);
        assertStatus(() -> service.publish("owner", "Ana", caronaComPacote), HttpStatus.BAD_REQUEST);

        BoardDtos.CreateBoardRequest caronaComEmpresa = new BoardDtos.CreateBoardRequest(BoardKind.SEAT, place("A", "SP"),
                place("B", "SP"), soon(), null, UUID.randomUUID(), null);
        assertStatus(() -> service.publish("owner", "Ana", caronaComEmpresa), HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ consultar

    @Test
    void detalheRespeitaQuemPodeVer() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findById(requestId)).thenReturn(Optional.of(b));

        assertThat(service.get(requestId, "owner", false).mine()).isTrue();

        when(offers.findByRequestIdInAndDeletedAtIsNullOrderByPriceAsc(any())).thenReturn(List.of(offer("driver-1", new BigDecimal("10"))));
        assertThat(service.get(requestId, "driver-1", false).mine()).isFalse();

        assertThat(service.get(requestId, "outro-motorista", true).mine()).isFalse();

        assertStatus(() -> service.get(requestId, "estranho", false), HttpStatus.NOT_FOUND);
    }

    @Test
    void detalheDePedidoExpiradoSoParaODonoOuQuemOfertou() {
        BoardRequest b = board(BoardKind.SEAT);
        b.setDesiredAt(LocalDateTime.now(clock).minusMinutes(1));
        when(requests.findById(requestId)).thenReturn(Optional.of(b));

        assertThat(service.get(requestId, "owner", true).status()).isEqualTo(BoardStatus.EXPIRED);
        assertStatus(() -> service.get(requestId, "outro", true), HttpStatus.NOT_FOUND);
    }

    @Test
    void pedidoInexistenteOuApagadoDa404() {
        when(requests.findById(requestId)).thenReturn(Optional.empty());
        assertStatus(() -> service.get(requestId, "owner", false), HttpStatus.NOT_FOUND);

        BoardRequest apagado = board(BoardKind.SEAT);
        apagado.setDeletedAt(Instant.now());
        when(requests.findById(requestId)).thenReturn(Optional.of(apagado));
        assertStatus(() -> service.get(requestId, "owner", false), HttpStatus.NOT_FOUND);
    }

    @Test
    void ofertasSoParaODonoEDescartamRetiradas() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findById(requestId)).thenReturn(Optional.of(b));
        BoardOffer pendente = offer("d1", new BigDecimal("10"));
        BoardOffer retirada = offer("d2", new BigDecimal("12"));
        retirada.setStatus(BoardOfferStatus.WITHDRAWN);
        when(offers.findByRequestIdInAndDeletedAtIsNullOrderByPriceAsc(any())).thenReturn(List.of(pendente, retirada));

        assertThat(service.offersFor(requestId, "owner")).hasSize(1);
        assertStatus(() -> service.offersFor(requestId, "estranho"), HttpStatus.NOT_FOUND);
    }

    @Test
    void minhasSolicitacoesIncluemAsDaEmpresa() {
        when(membership.companyIdsOf("owner")).thenReturn(List.of(UUID.randomUUID()));
        when(requests.findMine(eq("owner"), any())).thenReturn(List.of(board(BoardKind.SEAT)));
        assertThat(service.mine("owner")).hasSize(1);
    }

    @Test
    void semEmpresaConsultaComIdZerado() {
        when(membership.companyIdsOf("owner")).thenReturn(List.of());
        when(requests.findMine("owner", List.of(new UUID(0, 0)))).thenReturn(List.of());
        assertThat(service.mine("owner")).isEmpty();
    }

    // ------------------------------------------------------------------ cancelar

    @Test
    void donoCancelaEPendentesSaoRecusadas() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        when(requests.save(any())).thenAnswer(inv -> { BoardRequest saved = inv.getArgument(0); ReflectionTestUtils.setField(saved, "id", requestId); return saved; });
        BoardOffer pendente = offer("d1", new BigDecimal("10"));
        when(offers.findByRequestIdAndStatus(requestId, BoardOfferStatus.PENDING)).thenReturn(List.of(pendente));

        BoardDtos.BoardResponse r = service.cancel(requestId, "owner");

        assertThat(r.status()).isEqualTo(BoardStatus.CANCELLED);
        assertThat(pendente.getStatus()).isEqualTo(BoardOfferStatus.DECLINED);
        verify(notifier).driver(eq("d1"), eq("BOARD_OFFER_DECLINED"), any(), any(), anyString());
    }

    @Test
    void naoCancelaPedidoDeOutroNemForaDoMural() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        assertStatus(() -> service.cancel(requestId, "estranho"), HttpStatus.NOT_FOUND);

        b.setStatus(BoardStatus.ACCEPTED);
        assertStatus(() -> service.cancel(requestId, "owner"), HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------ aceitar oferta

    @Test
    void aceitarOfertaDeCaronaEmViagemNovaConfirmaReserva() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        when(requests.save(any())).thenAnswer(inv -> { BoardRequest saved = inv.getArgument(0); ReflectionTestUtils.setField(saved, "id", requestId); return saved; });
        BoardOffer offer = offer("driver-1", new BigDecimal("80.00"));
        when(offers.findById(offer.getId())).thenReturn(Optional.of(offer));

        Ride nova = mock(Ride.class);
        UUID newRideId = UUID.randomUUID();
        when(nova.getId()).thenReturn(newRideId);
        when(rideService.createPrivateSeatRide(anyString(), anyString(), any(), anyString(), anyString(), any(),
                anyString(), anyString(), any(), any(), any())).thenReturn(nova);
        RidePassenger booking = mock(RidePassenger.class);
        UUID bookingId = UUID.randomUUID();
        when(booking.getId()).thenReturn(bookingId);
        when(passengers.confirmFromBoard(eq(newRideId), eq("owner"), eq("Ana Souza"), any(), any(), any()))
                .thenReturn(booking);

        BoardDtos.BoardResponse r = service.accept(requestId, offer.getId(), "owner");

        assertThat(r.status()).isEqualTo(BoardStatus.ACCEPTED);
        assertThat(r.bookingId()).isEqualTo(bookingId);
        assertThat(offer.getStatus()).isEqualTo(BoardOfferStatus.ACCEPTED);
        verify(notifier).driver(eq("driver-1"), eq("BOARD_OFFER_ACCEPTED"), eq(newRideId), any(), anyString());
    }

    @Test
    void aceitarOfertaDeEncomendaEmCaronaExistenteCriaEncomenda() {
        BoardRequest b = board(BoardKind.PACKAGE);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        when(requests.save(any())).thenAnswer(inv -> { BoardRequest saved = inv.getArgument(0); ReflectionTestUtils.setField(saved, "id", requestId); return saved; });

        Ride ride = Fixtures.ride();
        ride.setDepartureDate(b.getDesiredAt());
        when(rides.findById(any())).thenReturn(Optional.of(ride));
        when(rides.findByIdForUpdate(any())).thenReturn(Optional.of(ride));
        when(rides.findById(any())).thenReturn(Optional.of(ride));
        when(rides.locateOnRoute(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(match(0.1, 0.8)));
        Delivery saved = mock(Delivery.class);
        when(saved.getId()).thenReturn(UUID.randomUUID());
        when(deliveries.save(any())).thenReturn(saved);

        BoardOffer offer = offer("driver-1", new BigDecimal("30.00"));
        offer.setRideId(UUID.randomUUID());
        when(offers.findById(offer.getId())).thenReturn(Optional.of(offer));

        BoardDtos.BoardResponse r = service.accept(requestId, offer.getId(), "owner");

        assertThat(r.deliveryId()).isNotNull();
        assertThat(offer.getStatus()).isEqualTo(BoardOfferStatus.ACCEPTED);
        verify(capacity).assertFits(eq(ride), any(), eq(null));
    }

    @Test
    void aceitarOfertaDeEncomendaEmViagemNova() {
        BoardRequest b = board(BoardKind.PACKAGE);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        when(requests.save(any())).thenAnswer(inv -> { BoardRequest saved = inv.getArgument(0); ReflectionTestUtils.setField(saved, "id", requestId); return saved; });

        Ride nova = mock(Ride.class);
        UUID id = UUID.randomUUID();
        when(nova.getId()).thenReturn(id);
        when(nova.getPlatformFeePercent()).thenReturn(new BigDecimal("10"));
        when(rideService.createPrivateCargoRide(anyString(), anyString(), any(), anyString(), anyString(), any(),
                anyString(), anyString(), any(), any(), any())).thenReturn(nova);
        when(rides.findById(id)).thenReturn(Optional.of(nova));
        Delivery saved = mock(Delivery.class);
        when(saved.getId()).thenReturn(UUID.randomUUID());
        when(deliveries.save(any())).thenReturn(saved);

        BoardOffer offer = offer("driver-1", new BigDecimal("30.00"));
        when(offers.findById(offer.getId())).thenReturn(Optional.of(offer));

        BoardDtos.BoardResponse r = service.accept(requestId, offer.getId(), "owner");
        assertThat(r.deliveryId()).isNotNull();
    }

    @Test
    void aceiteValidaDonoEstadoEOferta() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        assertStatus(() -> service.accept(requestId, UUID.randomUUID(), "estranho"), HttpStatus.NOT_FOUND);

        when(offers.findById(any())).thenReturn(Optional.empty());
        assertStatus(() -> service.accept(requestId, UUID.randomUUID(), "owner"), HttpStatus.NOT_FOUND);

        b.setStatus(BoardStatus.CANCELLED);
        assertStatus(() -> service.accept(requestId, UUID.randomUUID(), "owner"), HttpStatus.CONFLICT);
    }

    @Test
    void ofertaDeOutroPedidoNaoEhAceita() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        BoardOffer deOutro = offer("d1", new BigDecimal("10"));
        deOutro.setRequestId(UUID.randomUUID());
        when(offers.findById(deOutro.getId())).thenReturn(Optional.of(deOutro));
        assertStatus(() -> service.accept(requestId, deOutro.getId(), "owner"), HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------ mural do motorista

    @Test
    void muralDaRegiaoValidaPosicao() {
        assertStatus(() -> service.region("driver-1", 200.0, 10.0), HttpStatus.BAD_REQUEST);
    }

    @Test
    void muralDaRegiaoSemPosicaoEComPosicao() {
        when(requests.findForDriver(eq("driver-1"), anyBoolean(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of());
        assertThat(service.region("driver-1", null, null)).isEmpty();
        assertThat(service.region("driver-1", -23.5, -46.6)).isEmpty();
    }

    @Test
    void muralDaRegiaoEscondePedidosDaPropriaEmpresa() {
        UUID minhaEmpresa = UUID.randomUUID();
        BoardRequest daEmpresa = board(BoardKind.SEAT);
        daEmpresa.setCompanyId(minhaEmpresa);
        BoardRequest deOutro = board(BoardKind.SEAT);
        BoardMatch match = mock(BoardMatch.class);
        when(match.getId()).thenReturn(requestId);
        when(requests.findForDriver(eq("driver-1"), anyBoolean(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(match));
        when(requests.findAllById(any())).thenReturn(List.of(daEmpresa));
        when(membership.companyIdsOf("driver-1")).thenReturn(List.of(minhaEmpresa));

        assertThat(service.region("driver-1", -23.5, -46.6)).isEmpty();

        when(requests.findAllById(any())).thenReturn(List.of(deOutro));
        assertThat(service.region("driver-1", -23.5, -46.6)).hasSize(1);
    }

    @Test
    void ofertasQueEuFiz() {
        BoardOffer o = offer("driver-1", new BigDecimal("10"));
        when(offers.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(eq("driver-1"), any()))
                .thenReturn(List.of(o));
        when(requests.findAllById(any())).thenReturn(List.of(board(BoardKind.SEAT)));
        assertThat(service.offered("driver-1")).hasSize(1);
    }

    // ------------------------------------------------------------------ oferecer

    @Test
    void motoristaSeOfereceComCaronaPublicada() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        Ride ride = Fixtures.ride();
        ride.setDepartureDate(b.getDesiredAt());
        when(rides.findById(any())).thenReturn(Optional.of(ride));
        when(rides.findByIdForUpdate(any())).thenReturn(Optional.of(ride));
        when(rides.locateOnRoute(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(match(0.1, 0.8)));
        when(offers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BoardDtos.CreateBoardOffer body = new BoardDtos.CreateBoardOffer(new BigDecimal("45.00"), UUID.randomUUID(), null, "  oi  ");
        BoardDtos.BoardOfferResponse r = service.offer(requestId, "driver-1", "Carlos", body);

        assertThat(r.price()).isEqualByComparingTo("45.00");
        assertThat(r.message()).isEqualTo("oi");
        verify(notifier).passenger(eq("owner"), eq("BOARD_OFFER"), any(), eq(requestId), anyString());
    }

    @Test
    void motoristaSeOfereceComViagemNova() {
        BoardRequest b = board(BoardKind.PACKAGE);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        when(offers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID company = UUID.randomUUID();
        b.setCompanyId(company);
        BoardDtos.CreateBoardOffer body = new BoardDtos.CreateBoardOffer(new BigDecimal("45.00"), null, UUID.randomUUID(), null);
        BoardDtos.BoardOfferResponse r = service.offer(requestId, "driver-1", null, body);

        assertThat(r.newRide()).isTrue();
        verify(rideService).requireVehicle(any(), eq("driver-1"));
        verify(notifier).company(eq(company), eq("BOARD_OFFER"), any(), eq(requestId), anyString());
    }

    @Test
    void ofertaValidaDonoEstadoDuplicidadeEVeiculo() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        BoardDtos.CreateBoardOffer valida = new BoardDtos.CreateBoardOffer(new BigDecimal("10"), UUID.randomUUID(), null, null);

        assertStatus(() -> service.offer(requestId, "owner", "Ana", valida), HttpStatus.CONFLICT); // próprio pedido

        BoardDtos.CreateBoardOffer semViagem = new BoardDtos.CreateBoardOffer(new BigDecimal("10"), null, null, null);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", semViagem), HttpStatus.BAD_REQUEST);

        BoardDtos.CreateBoardOffer duasViagens = new BoardDtos.CreateBoardOffer(new BigDecimal("10"), UUID.randomUUID(), UUID.randomUUID(), null);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", duasViagens), HttpStatus.BAD_REQUEST);

        when(offers.existsByRequestIdAndDriverSubAndStatus(requestId, "driver-1", BoardOfferStatus.PENDING)).thenReturn(true);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", valida), HttpStatus.CONFLICT);

        when(offers.existsByRequestIdAndDriverSubAndStatus(requestId, "driver-1", BoardOfferStatus.PENDING)).thenReturn(false);
        b.setStatus(BoardStatus.CANCELLED);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", valida), HttpStatus.CONFLICT);
    }

    @Test
    void caronaQueNaoServeBloqueiaAOferta() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        Ride ride = Fixtures.ride();
        ride.setVisibility(RideVisibility.PRIVATE);
        when(rides.findById(any())).thenReturn(Optional.of(ride));
        when(rides.findByIdForUpdate(any())).thenReturn(Optional.of(ride));

        BoardDtos.CreateBoardOffer body = new BoardDtos.CreateBoardOffer(new BigDecimal("10"), UUID.randomUUID(), null, null);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.CONFLICT);
    }

    @Test
    void caronaDeOutroMotoristaNaoServe() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        Ride ride = Fixtures.ride();
        ride.setDriverSub("outro");
        when(rides.findById(any())).thenReturn(Optional.of(ride));
        when(rides.findByIdForUpdate(any())).thenReturn(Optional.of(ride));
        BoardDtos.CreateBoardOffer body = new BoardDtos.CreateBoardOffer(new BigDecimal("10"), UUID.randomUUID(), null, null);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.NOT_FOUND);
    }

    @Test
    void regrasDaCaronaParaOfertaDeEncomenda() {
        BoardRequest b = board(BoardKind.PACKAGE);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        Ride ride = Fixtures.ride();
        ride.setType(RideType.PASSENGER); // não leva encomenda
        when(rides.findById(any())).thenReturn(Optional.of(ride));
        when(rides.findByIdForUpdate(any())).thenReturn(Optional.of(ride));
        BoardDtos.CreateBoardOffer body = new BoardDtos.CreateBoardOffer(new BigDecimal("10"), UUID.randomUUID(), null, null);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.CONFLICT);
    }

    @Test
    void caronaSemVagasOuSemRotaNaoServe() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        Ride semVagas = Fixtures.ride();
        semVagas.setAvailableSeats(0);
        when(rides.findById(any())).thenReturn(Optional.of(semVagas));
        when(rides.findByIdForUpdate(any())).thenReturn(Optional.of(semVagas));
        BoardDtos.CreateBoardOffer body = new BoardDtos.CreateBoardOffer(new BigDecimal("10"), UUID.randomUUID(), null, null);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.CONFLICT);

        Ride semRota = Fixtures.ride();
        when(rides.findById(any())).thenReturn(Optional.of(semRota));
        when(rides.findByIdForUpdate(any())).thenReturn(Optional.of(semRota));
        when(rides.locateOnRoute(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.CONFLICT);

        when(rides.locateOnRoute(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(match(0.8, 0.2)));
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.CONFLICT);
    }

    @Test
    void horarioDaCaronaPrecisaCombinar() {
        BoardRequest b = board(BoardKind.SEAT);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(b));
        Ride ride = Fixtures.ride();
        ride.setDepartureDate(LocalDateTime.now(clock).plusDays(3));
        when(rides.findById(any())).thenReturn(Optional.of(ride));
        when(rides.findByIdForUpdate(any())).thenReturn(Optional.of(ride));
        when(rides.locateOnRoute(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(match(0.1, 0.8)));
        BoardDtos.CreateBoardOffer body = new BoardDtos.CreateBoardOffer(new BigDecimal("10"), UUID.randomUUID(), null, null);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.CONFLICT);
    }

    @Test
    void retirarOferta() {
        BoardOffer o = offer("driver-1", new BigDecimal("10"));
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(board(BoardKind.SEAT)));
        when(offers.findById(o.getId())).thenReturn(Optional.of(o));

        service.withdraw(requestId, o.getId(), "driver-1");
        assertThat(o.getStatus()).isEqualTo(BoardOfferStatus.WITHDRAWN);

        assertStatus(() -> service.withdraw(requestId, o.getId(), "outro"), HttpStatus.NOT_FOUND);
        when(offers.findById(o.getId())).thenReturn(Optional.of(o));
        assertStatus(() -> service.withdraw(requestId, o.getId(), "driver-1"), HttpStatus.NOT_FOUND); // já retirada
    }

    // ------------------------------------------------------------------ apoio

    private BoardOffer offer(String driverSub, BigDecimal price) {
        BoardOffer o = new BoardOffer();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        o.setRequestId(requestId);
        o.setDriverSub(driverSub);
        o.setDriverName("Motorista");
        o.setPrice(price);
        return o;
    }

    private static RideRouteMatch match(double pickup, double dropoff) {
        return new RideRouteMatch() {
            @Override public UUID getId() { return UUID.randomUUID(); }
            @Override public Double getPickupFraction() { return pickup; }
            @Override public Double getDropoffFraction() { return dropoff; }
            @Override public Double getPickupDistance() { return 100.0; }
            @Override public Double getDropoffDistance() { return 100.0; }
        };
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(status);
    }
}
