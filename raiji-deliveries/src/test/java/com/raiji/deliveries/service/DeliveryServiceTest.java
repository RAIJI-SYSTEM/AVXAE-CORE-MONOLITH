package com.raiji.deliveries.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.PaymentStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.common.enums.RideType;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.dto.DeliveryCreateRequest;
import com.raiji.deliveries.dto.DeliveryResponse;
import com.raiji.deliveries.dto.PackageRequest;
import com.raiji.deliveries.domain.PackageSize;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.rides.cargo.CargoCapacityService;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideVisibility;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.geo.Place;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.repository.RideRouteMatch;
import com.raiji.users.company.CompanyMembership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.raiji.deliveries.support.Fixtures;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryServiceTest {

    @Mock DeliveryRepository deliveries;
    @Mock RideRepository rides;
    @Mock CargoCapacityService capacity;
    @Mock CompanyMembership membership;
    @Mock DeliveryNotifier notifier;
    @Mock DeliveryRefunds refunds;

    DeliveryService service;

    private final UUID rideId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

    @BeforeEach
    void setUp() {
        service = new DeliveryService(deliveries, rides, capacity, membership, notifier, refunds, clock);
    }

    private Ride ride() {
        return Fixtures.ride();
    }

    private DeliveryCreateRequest body() {
        return new DeliveryCreateRequest(Fixtures.pkgRequest(), null, null, null, "  Ana  ");
    }

    private void stubSave() {
        when(deliveries.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------ request

    @Test
    void pedidoDeEncomendaRegistraCodigosEAvisaOMotorista() {
        Ride ride = ride();
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        stubSave();

        DeliveryResponse r = service.request(rideId, "sender", "token-name", body());

        assertThat(r.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(r.pickupCode()).matches("\\d{4}");
        assertThat(r.deliveryKeyword()).matches("[A-Z]+-\\d{2}");
        assertThat(r.senderName()).isEqualTo("Ana");
        assertThat(r.price()).isEqualByComparingTo("20.00");
        assertThat(r.fullRoute()).isTrue();
        verify(notifier).driver(eq("driver-1"), eq("DELIVERY_REQUEST"), any(), any(), anyString());
    }

    @Test
    void usaONomeDoTokenQuandoNaoInformado() {
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        stubSave();
        DeliveryResponse r = service.request(rideId, "sender", "  Do Token  ",
                new DeliveryCreateRequest(Fixtures.pkgRequest(), null, null, null, null));
        assertThat(r.senderName()).isEqualTo("Do Token");
    }

    @Test
    void recusaCaronaInexistenteApagadaOuPrivada() {
        when(rides.findById(rideId)).thenReturn(Optional.empty());
        assertStatus(() -> service.request(rideId, "s", "n", body()), HttpStatus.NOT_FOUND);

        Ride deleted = ride();
        deleted.setDeletedAt(Instant.now());
        when(rides.findById(rideId)).thenReturn(Optional.of(deleted));
        assertStatus(() -> service.request(rideId, "s", "n", body()), HttpStatus.NOT_FOUND);

        Ride privada = ride();
        privada.setVisibility(RideVisibility.PRIVATE);
        when(rides.findById(rideId)).thenReturn(Optional.of(privada));
        assertStatus(() -> service.request(rideId, "s", "n", body()), HttpStatus.NOT_FOUND);
    }

    @Test
    void naoDeixaEnviarNaPropriaCarona() {
        Ride ride = ride();
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        assertStatus(() -> service.request(rideId, "driver-1", "n", body()), HttpStatus.CONFLICT);
    }

    @Test
    void exigeCaronaQueLevaEncomendaEAceitaPedidos() {
        Ride semCarga = ride();
        semCarga.setType(RideType.PASSENGER);
        when(rides.findById(rideId)).thenReturn(Optional.of(semCarga));
        assertStatus(() -> service.request(rideId, "s", "n", body()), HttpStatus.CONFLICT);

        Ride fechada = ride();
        fechada.setBookingsClosed(true);
        when(rides.findById(rideId)).thenReturn(Optional.of(fechada));
        assertStatus(() -> service.request(rideId, "s", "n", body()), HttpStatus.CONFLICT);

        Ride passada = ride();
        passada.setDepartureDate(LocalDateTime.now().minusDays(1));
        when(rides.findById(rideId)).thenReturn(Optional.of(passada));
        assertStatus(() -> service.request(rideId, "s", "n", body()), HttpStatus.CONFLICT);
    }

    @Test
    void empresaExigeMembroAtivo() {
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        UUID company = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(membership).requireActiveMember(company, "s");

        assertStatus(() -> service.request(rideId, "s", "n",
                new DeliveryCreateRequest(Fixtures.pkgRequest(), null, null, company, null)), HttpStatus.FORBIDDEN);
    }

    @Test
    void pesoAcimaDoTamanhoFalhaAntesDeSalvar() {
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        PackageRequest pesado = new PackageRequest(PackageSize.ENVELOPE, "Doc", new BigDecimal("9"),
                null, null, null, false, null, "Bia", "11999999999");
        assertStatus(() -> service.request(rideId, "s", "n",
                new DeliveryCreateRequest(pesado, null, null, null, null)), HttpStatus.BAD_REQUEST);
        verify(deliveries, never()).save(any());
    }

    @Test
    void trechoCalculaPrecoProporcionalEExigeRota() {
        Ride ride = ride();
        ride.setRoute(mock(org.locationtech.jts.geom.LineString.class));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        stubSave();
        RideRouteMatch m = match(0.2, 0.6, 100, 100);
        when(rides.locateOnRoute(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(m));

        DeliveryResponse r = service.request(rideId, "s", "n",
                new DeliveryCreateRequest(Fixtures.pkgRequest(), new Place(-23.55, -46.63, "A"),
                        new Place(-22.90, -47.06, "B"), null, null));

        assertThat(r.fullRoute()).isFalse();
        assertThat(r.price()).isEqualByComparingTo("8.00"); // 40% de R$ 20
    }

    @Test
    void trechoSemRotaFalha() {
        Ride ride = ride();
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        assertStatus(() -> service.request(rideId, "s", "n",
                new DeliveryCreateRequest(Fixtures.pkgRequest(), new Place(-23.55, -46.63, "A"), null, null, null)),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void trechoComDesvioGrandeOuSentidoErradoFalha() {
        Ride ride = ride();
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        when(rides.locateOnRoute(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(match(0.2, 0.6, 9_000, 10)));
        assertStatus(() -> service.request(rideId, "s", "n",
                new DeliveryCreateRequest(Fixtures.pkgRequest(), new Place(1.0, 1.0, "A"), new Place(2.0, 2.0, "B"), null, null)),
                HttpStatus.BAD_REQUEST);

        when(rides.locateOnRoute(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(match(0.7, 0.2, 10, 10)));
        assertStatus(() -> service.request(rideId, "s", "n",
                new DeliveryCreateRequest(Fixtures.pkgRequest(), new Place(1.0, 1.0, "A"), new Place(2.0, 2.0, "B"), null, null)),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void trechoSemResultadoDaBuscaFalha() {
        Ride ride = ride();
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        when(rides.locateOnRoute(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.empty());
        assertStatus(() -> service.request(rideId, "s", "n",
                new DeliveryCreateRequest(Fixtures.pkgRequest(), new Place(1.0, 1.0, "A"), null, null, null)),
                HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ listForDriver / decide

    @Test
    void motoristaListaAsEncomendasDaSuaCarona() {
        Ride ride = ride();
        when(rides.findById(rideId)).thenReturn(Optional.of(ride));
        when(deliveries.findByRideIdAndDeletedAtIsNull(rideId)).thenReturn(List.of(Fixtures.delivery(rideId, DeliveryStatus.PENDING)));

        List<DeliveryResponse> list = service.listForDriver(rideId, "driver-1");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).pickupCode()).isNull(); // motorista não vê códigos
    }

    @Test
    void outroMotoristaNaoAcessaACarona() {
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        assertStatus(() -> service.listForDriver(rideId, "outro"), HttpStatus.FORBIDDEN);
    }

    @Test
    void aceiteConfereCapacidadeEAvisaORemetente() {
        Ride ride = ride();
        UUID deliveryId = UUID.randomUUID();
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        stubSave();

        DeliveryResponse r = service.decide(rideId, deliveryId, "driver-1", true, null);

        assertThat(r.status()).isEqualTo(DeliveryStatus.CONFIRMED);
        assertThat(r.pickupCode()).isNull();
        verify(capacity).assertFits(eq(ride), any(), eq(null));
        verify(notifier).sender(eq(d), eq("DELIVERY_ACCEPTED"), org.mockito.ArgumentMatchers.contains("Pague pelo app"));
    }

    @Test
    void aceiteDeEncomendaGratuitaNaoFalaEmPagamento() {
        Ride ride = ride();
        UUID deliveryId = UUID.randomUUID();
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        d.setPrice(null);
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        stubSave();

        service.decide(rideId, deliveryId, "driver-1", true, null);

        verify(notifier).sender(eq(d), eq("DELIVERY_ACCEPTED"), org.mockito.AdditionalMatchers.not(org.mockito.ArgumentMatchers.contains("Pague pelo app")));
    }

    @Test
    void recusaExigeMotivo() {
        Ride ride = ride();
        UUID deliveryId = UUID.randomUUID();
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));

        assertStatus(() -> service.decide(rideId, deliveryId, "driver-1", false, "  "), HttpStatus.BAD_REQUEST);

        stubSave();
        DeliveryResponse r = service.decide(rideId, deliveryId, "driver-1", false, "  sem espaço  ");
        assertThat(r.status()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(r.cancellationReason()).isEqualTo("sem espaço");
        verify(notifier).sender(eq(d), eq("DELIVERY_REJECTED"), org.mockito.ArgumentMatchers.contains("sem espaço"));
    }

    @Test
    void naoDecideViagemQueJaComecouNemPedidoJaDecidido() {
        Ride ride = ride();
        ride.setStatus(RideStatus.IN_PROGRESS);
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride));
        assertStatus(() -> service.decide(rideId, UUID.randomUUID(), "driver-1", true, null), HttpStatus.CONFLICT);

        Ride planned = ride();
        UUID deliveryId = UUID.randomUUID();
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(planned));
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED)));
        assertStatus(() -> service.decide(rideId, deliveryId, "driver-1", true, null), HttpStatus.CONFLICT);
    }

    @Test
    void encomendaDeOutraCaronaNaoEEncontrada() {
        when(rides.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride()));
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(Fixtures.delivery(UUID.randomUUID(), DeliveryStatus.PENDING)));
        assertStatus(() -> service.decide(rideId, deliveryId, "driver-1", true, null), HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------ coletar / entregar

    @Test
    void coletaComCodigoCerto() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        stubSave();

        DeliveryResponse r = service.pickUp(deliveryId, "driver-1", " 1234 ");

        assertThat(r.status()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        verify(notifier).sender(eq(d), eq("DELIVERY_PICKED_UP"), org.mockito.ArgumentMatchers.contains("coletada"));
    }

    @Test
    void coletaComCodigoErradoContaTentativa() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));

        assertStatus(() -> service.pickUp(deliveryId, "driver-1", "9999"), HttpStatus.BAD_REQUEST);

        assertThat(d.getFailedCodeAttempts()).isEqualTo(1);
        verify(deliveries).saveAndFlush(d);
    }

    @Test
    void bloqueiaAposMuitasTentativasErradas() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED);
        d.setFailedCodeAttempts(DeliveryCodes.MAX_FAILED_ATTEMPTS);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));

        assertStatus(() -> service.pickUp(deliveryId, "driver-1", "1234"), HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void coletaExigeStatusEViagemAtiva() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(Fixtures.delivery(rideId, DeliveryStatus.PENDING)));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        assertStatus(() -> service.pickUp(deliveryId, "driver-1", "1234"), HttpStatus.CONFLICT);

        Delivery confirmada = Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED);
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(confirmada));
        Ride cancelada = ride();
        cancelada.setStatus(RideStatus.CANCELLED);
        when(rides.findById(rideId)).thenReturn(Optional.of(cancelada));
        assertStatus(() -> service.pickUp(deliveryId, "driver-1", "1234"), HttpStatus.CONFLICT);
    }

    @Test
    void entregaComPalavraChaveCerta() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.IN_TRANSIT);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        stubSave();

        DeliveryResponse r = service.deliver(deliveryId, "driver-1", "sol 42");

        assertThat(r.status()).isEqualTo(DeliveryStatus.DELIVERED);
        verify(notifier).sender(eq(d), eq("DELIVERY_DELIVERED"), org.mockito.ArgumentMatchers.contains("entregue"));
    }

    @Test
    void entregaExigeStatusEmTransito() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED)));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        assertStatus(() -> service.deliver(deliveryId, "driver-1", "SOL-42"), HttpStatus.CONFLICT);
    }

    @Test
    void palavraChaveErradaContaTentativa() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.IN_TRANSIT);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        assertStatus(() -> service.deliver(deliveryId, "driver-1", "LUA-00"), HttpStatus.BAD_REQUEST);
        assertThat(d.getFailedCodeAttempts()).isEqualTo(1);
    }

    @Test
    void coletarOuEntregarEncomendaInexistenteDa404() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.empty());
        assertStatus(() -> service.pickUp(deliveryId, "driver-1", "1234"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.deliver(deliveryId, "driver-1", "X"), HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------ cancelar / consultar

    @Test
    void remetenteCancelaEAvisaOMotorista() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        stubSave();

        DeliveryResponse r = service.cancel(deliveryId, "sender-1", "  desisti  ");

        assertThat(r.status()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(r.cancellationReason()).isEqualTo("desisti");
        verify(refunds).refundIfPaid(d, "sender-1");
        verify(notifier).driver(eq("driver-1"), eq("DELIVERY_CANCELLED"), any(), any(), org.mockito.ArgumentMatchers.contains("desisti"));
    }

    @Test
    void motoristaCancelaEAvisaORemetente() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        stubSave();

        service.cancel(deliveryId, "driver-1", null);

        verify(notifier).sender(eq(d), eq("DELIVERY_CANCELLED"), org.mockito.ArgumentMatchers.contains("motorista cancelou"));
        assertThat(d.getCancellationReason()).isNull();
    }

    @Test
    void membroDaEmpresaCancelaComoRemetente() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        UUID company = UUID.randomUUID();
        d.setCompanyId(company);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        when(membership.isActiveMember(company, "member")).thenReturn(true);
        stubSave();

        service.cancel(deliveryId, "member", null);
        verify(notifier).driver(anyString(), eq("DELIVERY_CANCELLED"), any(), any(), anyString());
    }

    @Test
    void naoCancelaSemPermissaoNemDepoisDaColeta() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        assertStatus(() -> service.cancel(deliveryId, "estranho", null), HttpStatus.NOT_FOUND);

        Delivery emTransito = Fixtures.delivery(rideId, DeliveryStatus.IN_TRANSIT);
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(emTransito));
        assertStatus(() -> service.cancel(deliveryId, "sender-1", null), HttpStatus.CONFLICT);
    }

    @Test
    void cancelarEncomendaOuCaronaInexistenteDa404() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.empty());
        assertStatus(() -> service.cancel(deliveryId, "s", null), HttpStatus.NOT_FOUND);

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(Fixtures.delivery(rideId, DeliveryStatus.PENDING)));
        when(rides.findById(rideId)).thenReturn(Optional.empty());
        assertStatus(() -> service.cancel(deliveryId, "s", null), HttpStatus.NOT_FOUND);
    }

    @Test
    void remetenteMotoristaEEmpresaPodemConsultar() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));

        assertThat(service.get(deliveryId, "sender-1").pickupCode()).isEqualTo("1234");
        assertThat(service.get(deliveryId, "driver-1").pickupCode()).isNull();

        UUID company = UUID.randomUUID();
        d.setCompanyId(company);
        when(membership.isActiveMember(company, "member")).thenReturn(true);
        assertThat(service.get(deliveryId, "member").pickupCode()).isEqualTo("1234");
    }

    @Test
    void estranhoNaoVeAEncomenda() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(ride()));
        assertStatus(() -> service.get(deliveryId, "estranho"), HttpStatus.NOT_FOUND);
    }

    @Test
    void encomendaSemCaronaAindaPodeSerVistaPeloRemetente() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        UUID deliveryId = UUID.randomUUID();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.empty());

        assertThat(service.get(deliveryId, "sender-1").ride()).isNull();
        assertStatus(() -> service.get(deliveryId, "driver-1"), HttpStatus.NOT_FOUND);
    }

    @Test
    void minhasEncomendasIncluemAsDaEmpresa() {
        Delivery pessoal = Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED);
        Delivery daEmpresa = Fixtures.delivery(UUID.randomUUID(), DeliveryStatus.PENDING);
        daEmpresa.setCompanyId(UUID.randomUUID());
        when(membership.companyIdsOf("sender-1")).thenReturn(List.of(daEmpresa.getCompanyId()));
        when(deliveries.findMine(eq("sender-1"), any())).thenReturn(List.of(pessoal, daEmpresa));
        when(rides.findAllById(any())).thenReturn(List.of(ride()));

        List<DeliveryResponse> list = service.mine("sender-1");

        assertThat(list).hasSize(2);
        assertThat(list.get(0).pickupCode()).isNotNull();
    }

    @Test
    void semEmpresasConsultaComIdZerado() {
        when(membership.companyIdsOf("sender-1")).thenReturn(List.of());
        when(deliveries.findMine("sender-1", List.of(new UUID(0, 0)))).thenReturn(List.of());
        assertThat(service.mine("sender-1")).isEmpty();
    }

    @Test
    void encomendasQueEuLevoComoMotorista() {
        Ride r = ride();
        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(eq("driver-1"), any()))
                .thenReturn(List.of(r));
        when(deliveries.findByRideIds(any())).thenReturn(List.of(Fixtures.delivery(r.getId(), DeliveryStatus.CONFIRMED)));
        when(rides.findAllById(any())).thenReturn(List.of(r));

        List<DeliveryResponse> list = service.driving("driver-1");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).pickupCode()).isNull();
    }

    @Test
    void semCaronasNaoConsultaEncomendas() {
        when(rides.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(eq("driver-1"), any()))
                .thenReturn(List.of());
        assertThat(service.driving("driver-1")).isEmpty();
        verifyNoInteractions(deliveries);
    }

    // ------------------------------------------------------------------ apoio

    private static RideRouteMatch match(double pickupFraction, double dropoffFraction, double pickupDistance, double dropoffDistance) {
        return new RideRouteMatch() {
            @Override public UUID getId() { return UUID.randomUUID(); }
            @Override public Double getPickupFraction() { return pickupFraction; }
            @Override public Double getDropoffFraction() { return dropoffFraction; }
            @Override public Double getPickupDistance() { return pickupDistance; }
            @Override public Double getDropoffDistance() { return dropoffDistance; }
        };
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(status);
    }
}
