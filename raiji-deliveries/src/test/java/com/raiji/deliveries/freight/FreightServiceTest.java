package com.raiji.deliveries.freight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Optional;
import java.util.UUID;

import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.freight.FreightDtos.CreateFreightRequest;
import com.raiji.deliveries.freight.FreightDtos.CreateOfferRequest;
import com.raiji.deliveries.freight.FreightDtos.FreightPlace;
import com.raiji.deliveries.freight.FreightDtos.FreightResponse;
import com.raiji.deliveries.freight.FreightDtos.OfferResponse;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.deliveries.service.DeliveryNotifier;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.service.RideService;
import com.raiji.users.company.Company;
import com.raiji.users.company.CompanyMembership;
import com.raiji.users.keycloak.KeycloakAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.raiji.deliveries.support.Fixtures;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreightServiceTest {

    @Mock FreightRequestRepository requests;
    @Mock FreightOfferRepository offers;
    @Mock DeliveryRepository deliveries;
    @Mock RideService rideService;
    @Mock CompanyMembership membership;
    @Mock KeycloakAdminService keycloak;
    @Mock DeliveryNotifier notifier;

    FreightService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

    @BeforeEach
    void setUp() {
        service = new FreightService(requests, offers, deliveries, rideService, membership, keycloak, notifier, clock);
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(companyId);
        when(company.displayName()).thenReturn("Transportes Lima");
        when(membership.requireActiveMember(any(), anyString())).thenReturn(company);
        when(membership.company(any())).thenReturn(company);
        when(membership.displayName(any())).thenReturn("Transportes Lima");
        when(offers.countByRequestIdAndStatus(any(), any())).thenReturn(0L);
        when(offers.findByRequestIdAndDeletedAtIsNullOrderByPriceAsc(any())).thenReturn(List.of());
        when(offers.findByRequestIdAndStatus(any(), any())).thenReturn(List.of());
    }

    private LocalDateTime future() {
        return LocalDateTime.now(clock).plusDays(2);
    }

    private FreightPlace place(String city) {
        return new FreightPlace(city, "sp", -23.55, -46.63, "Rua X");
    }

    private CreateFreightRequest body(String target, BigDecimal price) {
        return new CreateFreightRequest(target, price, place("São Paulo"), place("Campinas"), future(),
                Fixtures.pkgRequest(), "  cuidado  ");
    }

    private FreightRequest freight(FreightStatus status, String target) {
        FreightRequest f = new FreightRequest();
        ReflectionTestUtils.setField(f, "id", requestId);
        f.setCompanyId(companyId);
        f.setCreatedBySub("member");
        f.setTargetDriverSub(target);
        f.setStatus(status);
        f.setOriginCity("São Paulo");
        f.setOriginState("SP");
        f.setOriginLocation(GeoSupport.point(-23.55, -46.63));
        f.setOriginAddress("Rua A");
        f.setDestinationCity("Campinas");
        f.setDestinationState("SP");
        f.setDestinationLocation(GeoSupport.point(-22.90, -47.06));
        f.setDestinationAddress("Rua B");
        f.setDesiredDate(future());
        f.setPkg(Fixtures.pkg());
        f.setOfferedPrice(new BigDecimal("100.00"));
        return f;
    }

    private FreightOffer offer(FreightStatus ignored, String driverSub) {
        FreightOffer o = new FreightOffer();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        o.setRequestId(requestId);
        o.setDriverSub(driverSub);
        o.setDriverName("Carlos");
        o.setVehicleId(UUID.randomUUID());
        o.setPrice(new BigDecimal("90.00"));
        return o;
    }

    private void stubSave() {
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------ empresa

    @Test
    void criaFretePublico() {
        stubSave();
        FreightResponse r = service.create(companyId, "member", body(null, new BigDecimal("120.00")));

        assertThat(r.companyName()).isEqualTo("Transportes Lima");
        assertThat(r.direct()).isFalse();
        assertThat(r.originState()).isEqualTo("SP");
        assertThat(r.notes()).isEqualTo("cuidado");
        assertThat(r.offeredPrice()).isEqualByComparingTo("120.00");
        verify(notifier, never()).driver(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void criaPropostaDiretaEAvisaOMotorista() {
        stubSave();
        when(keycloak.effectiveRealmRoles("driver-1")).thenReturn(List.of("DRIVER"));
        when(membership.isActiveMember(companyId, "driver-1")).thenReturn(false);

        FreightResponse r = service.create(companyId, "member", body("driver-1", new BigDecimal("100.00")));

        assertThat(r.direct()).isTrue();
        assertThat(r.targetDriverSub()).isEqualTo("driver-1");
        verify(notifier).driver(eq("driver-1"), eq("FREIGHT_PROPOSAL"), eq(null), any(), anyString());
    }

    @Test
    void propostaDiretaValidaValorMembroEMotorista() {
        assertStatus(() -> service.create(companyId, "member", body("driver-1", null)), HttpStatus.BAD_REQUEST);

        when(membership.isActiveMember(companyId, "driver-1")).thenReturn(true);
        assertStatus(() -> service.create(companyId, "member", body("driver-1", new BigDecimal("10"))), HttpStatus.BAD_REQUEST);

        when(membership.isActiveMember(companyId, "driver-1")).thenReturn(false);
        when(keycloak.effectiveRealmRoles("driver-1")).thenReturn(List.of("USER"));
        assertStatus(() -> service.create(companyId, "member", body("driver-1", new BigDecimal("10"))), HttpStatus.BAD_REQUEST);

        when(keycloak.effectiveRealmRoles("driver-1")).thenThrow(new RuntimeException("keycloak"));
        assertStatus(() -> service.create(companyId, "member", body("driver-1", new BigDecimal("10"))), HttpStatus.BAD_REQUEST);
    }

    @Test
    void validaPrazoDoFrete() {
        LocalDateTime agora = LocalDateTime.now(clock);
        CreateFreightRequest cedo = new CreateFreightRequest(null, null, place("A"), place("B"), agora.plusMinutes(5),
                Fixtures.pkgRequest(), null);
        assertStatus(() -> service.create(companyId, "member", cedo), HttpStatus.BAD_REQUEST);

        CreateFreightRequest longe = new CreateFreightRequest(null, null, place("A"), place("B"), agora.plusMonths(4),
                Fixtures.pkgRequest(), null);
        assertStatus(() -> service.create(companyId, "member", longe), HttpStatus.BAD_REQUEST);
    }

    @Test
    void listaFretesDaEmpresa() {
        when(requests.findByCompanyIdAndDeletedAtIsNullOrderByCreatedAtDesc(companyId))
                .thenReturn(List.of(freight(FreightStatus.OPEN, null)));
        assertThat(service.listForCompany(companyId, "member")).hasSize(1);
    }

    @Test
    void detalheParaEmpresaMotoristaAlvoEOfertante() {
        FreightRequest publico = freight(FreightStatus.OPEN, null);
        when(requests.findById(requestId)).thenReturn(Optional.of(publico));

        when(membership.isActiveMember(companyId, "member")).thenReturn(true);
        assertThat(service.get(requestId, "member").companyName()).isEqualTo("Transportes Lima");
        assertThat(service.get(requestId, "member").recipientName()).isEqualTo("Bia");

        when(membership.isActiveMember(companyId, "driver-1")).thenReturn(false);
        assertThat(service.get(requestId, "driver-1").recipientName()).isNull();

        when(offers.findByRequestIdAndDeletedAtIsNullOrderByPriceAsc(requestId)).thenReturn(List.of(offer(null, "ofertante")));
        assertThat(service.get(requestId, "ofertante")).isNotNull();

        publico.setStatus(FreightStatus.ACCEPTED);
        assertStatus(() -> service.get(requestId, "estranho"), HttpStatus.NOT_FOUND);
    }

    @Test
    void detalheDeFreteDiretoParaOAlvo() {
        FreightRequest direto = freight(FreightStatus.OPEN, "driver-1");
        when(requests.findById(requestId)).thenReturn(Optional.of(direto));
        when(membership.isActiveMember(companyId, "driver-1")).thenReturn(false);

        assertThat(service.get(requestId, "driver-1").targetDriverSub()).isNull();
        assertStatus(() -> service.get(requestId, "outro"), HttpStatus.NOT_FOUND);
    }

    @Test
    void detalheInexistenteDa404() {
        when(requests.findById(requestId)).thenReturn(Optional.empty());
        assertStatus(() -> service.get(requestId, "member"), HttpStatus.NOT_FOUND);
    }

    @Test
    void listaOfertasDoFreteParaMembros() {
        when(requests.findById(requestId)).thenReturn(Optional.of(freight(FreightStatus.OPEN, null)));
        when(offers.findByRequestIdAndDeletedAtIsNullOrderByPriceAsc(requestId)).thenReturn(List.of(offer(null, "d1")));
        assertThat(service.offersFor(requestId, "member")).hasSize(1);
    }

    @Test
    void cancelaFreteEDeclinaOfertas() {
        FreightRequest direto = freight(FreightStatus.OPEN, "driver-1");
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(direto));
        stubSave();
        FreightOffer pendente = offer(null, "d2");
        when(offers.findByRequestIdAndStatus(requestId, OfferStatus.PENDING)).thenReturn(List.of(pendente));

        FreightResponse r = service.cancel(requestId, "member");

        assertThat(r.status()).isEqualTo(FreightStatus.CANCELLED);
        assertThat(pendente.getStatus()).isEqualTo(OfferStatus.DECLINED);
        verify(notifier).driver(eq("d2"), eq("FREIGHT_OFFER_DECLINED"), eq(null), any(), anyString());
        verify(notifier).driver(eq("driver-1"), eq("FREIGHT_CANCELLED"), eq(null), any(), anyString());
    }

    @Test
    void naoCancelaFreteFechado() {
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(freight(FreightStatus.ACCEPTED, null)));
        assertStatus(() -> service.cancel(requestId, "member"), HttpStatus.CONFLICT);
    }

    @Test
    void aceitaOfertaEFechaOFrete() {
        FreightRequest f = freight(FreightStatus.OPEN, null);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(f));
        stubSave();
        FreightOffer escolhida = offer(null, "driver-1");
        when(offers.findById(escolhida.getId())).thenReturn(Optional.of(escolhida));
        stubRideEPagamento();

        FreightResponse r = service.acceptOffer(requestId, escolhida.getId(), "member");

        assertThat(r.status()).isEqualTo(FreightStatus.ACCEPTED);
        assertThat(escolhida.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(f.getAcceptedOfferId()).isEqualTo(escolhida.getId());
        assertThat(f.getRideId()).isNotNull();
        assertThat(f.getDeliveryId()).isNotNull();
        verify(notifier).driver(eq("driver-1"), eq("FREIGHT_OFFER_ACCEPTED"), any(), any(), anyString());
    }

    @Test
    void aceiteValidaOfertaEStatus() {
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(freight(FreightStatus.OPEN, null)));
        when(offers.findById(any())).thenReturn(Optional.empty());
        assertStatus(() -> service.acceptOffer(requestId, UUID.randomUUID(), "member"), HttpStatus.NOT_FOUND);

        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(freight(FreightStatus.CANCELLED, null)));
        assertStatus(() -> service.acceptOffer(requestId, UUID.randomUUID(), "member"), HttpStatus.CONFLICT);
    }

    @Test
    void freteComDataPassadaNaoFecha() {
        FreightRequest f = freight(FreightStatus.OPEN, null);
        f.setDesiredDate(LocalDateTime.now(clock).minusDays(1));
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(f));
        FreightOffer escolhida = offer(null, "driver-1");
        when(offers.findById(escolhida.getId())).thenReturn(Optional.of(escolhida));

        assertStatus(() -> service.acceptOffer(requestId, escolhida.getId(), "member"), HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------ motorista

    @Test
    void propostasDiretasParaMim() {
        when(requests.findByTargetDriverSubAndStatusAndDeletedAtIsNullOrderByDesiredDateAsc("driver-1", FreightStatus.OPEN))
                .thenReturn(List.of(freight(FreightStatus.OPEN, "driver-1")));
        assertThat(service.forMe("driver-1")).hasSize(1);
    }

    @Test
    void fretesPublicosPertoDeMim() {
        when(requests.findOpenNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(freight(FreightStatus.OPEN, null)));
        when(membership.companyIdsOf("driver-1")).thenReturn(List.of());

        assertThat(service.nearby("driver-1", -23.5, -46.6, 10_000)).hasSize(1);
    }

    @Test
    void nearByValidaCoordenadasERaio() {
        assertThatThrownBy(() -> service.nearby("driver-1", 999, 0, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertStatus(() -> service.nearby("driver-1", -23.5, -46.6, 0), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.nearby("driver-1", -23.5, -46.6, 200_000), HttpStatus.BAD_REQUEST);
    }

    @Test
    void nearByEscondeFretesDaPropriaEmpresa() {
        FreightRequest daEmpresa = freight(FreightStatus.OPEN, null);
        when(requests.findOpenNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(daEmpresa));
        when(membership.companyIdsOf("driver-1")).thenReturn(List.of(companyId));
        assertThat(service.nearby("driver-1", -23.5, -46.6, 10_000)).isEmpty();
    }

    @Test
    void aceitaPropostaDireta() {
        FreightRequest f = freight(FreightStatus.OPEN, "driver-1");
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(f));
        stubSave();
        stubRideEPagamento();

        FreightResponse r = service.acceptDirect(requestId, "driver-1", "Carlos", UUID.randomUUID());

        assertThat(r.status()).isEqualTo(FreightStatus.ACCEPTED);
        assertThat(f.getDeliveryId()).isNotNull();
        verify(notifier).company(eq(companyId), eq("FREIGHT_ACCEPTED"), any(), any(), anyString());
    }

    @Test
    void aceiteDiretoSoParaOAlvo() {
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(freight(FreightStatus.OPEN, "driver-1")));
        assertStatus(() -> service.acceptDirect(requestId, "outro", "X", UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }

    @Test
    void recusaPropostaDireta() {
        FreightRequest f = freight(FreightStatus.OPEN, "driver-1");
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(f));
        stubSave();

        assertThat(service.declineDirect(requestId, "driver-1", "  sem espaço  ").status()).isEqualTo(FreightStatus.DECLINED);
        assertThat(f.getDeclineReason()).isEqualTo("sem espaço");
        verify(notifier).company(eq(companyId), eq("FREIGHT_DECLINED"), eq(null), any(), anyString());

        assertStatus(() -> service.declineDirect(requestId, "outro", null), HttpStatus.NOT_FOUND);
    }

    @Test
    void ofereceParaFretePublico() {
        FreightRequest f = freight(FreightStatus.OPEN, null);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(f));
        when(offers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OfferResponse r = service.offer(requestId, "driver-1", "Carlos", new CreateOfferRequest(new BigDecimal("80"), UUID.randomUUID(), "  oi  "));

        assertThat(r.price()).isEqualByComparingTo("80");
        assertThat(r.message()).isEqualTo("oi");
        verify(notifier).company(eq(companyId), eq("FREIGHT_OFFER"), eq(null), any(), anyString());
    }

    @Test
    void ofertaValidaFreteDiretoEmpresaDuplicadaEVeiculo() {
        FreightRequest direto = freight(FreightStatus.OPEN, "driver-1");
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(direto));
        CreateOfferRequest body = new CreateOfferRequest(new BigDecimal("80"), UUID.randomUUID(), null);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.NOT_FOUND);

        FreightRequest publico = freight(FreightStatus.OPEN, null);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(publico));
        when(membership.isActiveMember(companyId, "driver-1")).thenReturn(true);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.CONFLICT);

        when(membership.isActiveMember(companyId, "driver-1")).thenReturn(false);
        when(offers.existsByRequestIdAndDriverSubAndStatus(requestId, "driver-1", OfferStatus.PENDING)).thenReturn(true);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", body), HttpStatus.CONFLICT);

        when(offers.existsByRequestIdAndDriverSubAndStatus(requestId, "driver-1", OfferStatus.PENDING)).thenReturn(false);
        CreateOfferRequest semVeiculo = new CreateOfferRequest(new BigDecimal("80"), null, null);
        assertStatus(() -> service.offer(requestId, "driver-1", "Carlos", semVeiculo), HttpStatus.BAD_REQUEST);
    }

    @Test
    void retiraOferta() {
        FreightOffer o = offer(null, "driver-1");
        when(offers.findById(o.getId())).thenReturn(Optional.of(o));

        service.withdrawOffer(requestId, o.getId(), "driver-1");
        assertThat(o.getStatus()).isEqualTo(OfferStatus.WITHDRAWN);

        assertStatus(() -> service.withdrawOffer(requestId, o.getId(), "outro"), HttpStatus.NOT_FOUND);
        when(offers.findById(o.getId())).thenReturn(Optional.of(o));
        assertStatus(() -> service.withdrawOffer(requestId, o.getId(), "driver-1"), HttpStatus.NOT_FOUND);
    }

    @Test
    void minhasOfertas() {
        when(offers.findByDriverSubAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(eq("driver-1"), any()))
                .thenReturn(List.of(offer(null, "driver-1")));
        assertThat(service.myOffers("driver-1")).hasSize(1);
    }

    // ------------------------------------------------------------------ apoio

    private void stubRideEPagamento() {
        Ride ride = mock(Ride.class);
        UUID rideId = UUID.randomUUID();
        when(ride.getId()).thenReturn(rideId);
        when(ride.getOriginLocation()).thenReturn(GeoSupport.point(-23.55, -46.63));
        when(ride.getDestinationLocation()).thenReturn(GeoSupport.point(-22.90, -47.06));
        when(ride.getPlatformFeePercent()).thenReturn(new BigDecimal("10"));
        when(rideService.createPrivateCargoRide(anyString(), any(), any(), anyString(), anyString(), any(),
                anyString(), anyString(), any(), any(), any())).thenReturn(ride);

        Delivery delivery = mock(Delivery.class);
        UUID deliveryId = UUID.randomUUID();
        when(delivery.getId()).thenReturn(deliveryId);
        when(deliveries.save(any())).thenReturn(delivery);
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(status);
    }
}
