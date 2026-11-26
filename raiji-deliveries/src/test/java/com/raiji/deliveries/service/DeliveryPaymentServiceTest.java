package com.raiji.deliveries.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.PaymentStatus;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.repository.DeliveryRepository;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.payment.PaymentIntentChanged;
import com.raiji.rides.service.stripe.PaymentIntentInfo;
import com.raiji.rides.payment.PaymentResponse;
import com.raiji.rides.payment.PaymentService;
import com.raiji.rides.repository.RideRepository;
import com.raiji.rides.rewards.CouponChoice;
import com.raiji.rides.rewards.CouponService;
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
class DeliveryPaymentServiceTest {

    @Mock DeliveryRepository deliveries;
    @Mock RideRepository rides;
    @Mock DeliveryService deliveryService;
    @Mock PaymentService payments;
    @Mock DeliveryNotifier notifier;
    @Mock CouponService coupons;

    DeliveryPaymentService service;

    private final UUID deliveryId = UUID.randomUUID();
    private final UUID rideId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

    private final PaymentResponse response = new PaymentResponse(PaymentStatus.PROCESSING, 2000, null, "pi_1", "secret");

    @BeforeEach
    void setUp() {
        service = new DeliveryPaymentService(deliveries, rides, deliveryService, payments, notifier, coupons);
    }

    private Delivery confirmed() {
        Delivery d = Fixtures.delivery(rideId, DeliveryStatus.CONFIRMED);
        return d;
    }

    @Test
    void iniciaOPagamentoComOsDadosDoFrete() {
        Delivery d = confirmed();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(pagavelRide()));
        when(deliveryService.podeVerComoRemetente(d, "sender-1")).thenReturn(true);
        when(coupons.amountToCharge(eq(d), eq("sender-1"), eq(CouponChoice.KEEP), eq(2000L))).thenReturn(2000L);
        when(payments.start(eq(d), eq(2000L), anyString(), any(Map.class), anyString())).thenReturn(response);

        PaymentResponse r = service.start(deliveryId, "sender-1", CouponChoice.KEEP);

        assertThat(r.status()).isEqualTo(PaymentStatus.PROCESSING);
        verify(deliveries).save(d);
        verify(notifier, never()).driver(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void avisaOMotoristaQuandoOPagamentoJaEntrouNaInicializacao() {
        Delivery d = confirmed();
        d.setPaymentStatus(PaymentStatus.UNPAID);
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(pagavelRide()));
        when(deliveryService.podeVerComoRemetente(d, "sender-1")).thenReturn(true);
        when(coupons.amountToCharge(any(), anyString(), any(), anyLong())).thenReturn(2000L);
        when(payments.start(any(), anyLong(), anyString(), any(Map.class), anyString()))
                .thenAnswer(inv -> {
                    d.setPaymentStatus(PaymentStatus.PAID);
                    return response;
                });

        service.start(deliveryId, "sender-1", CouponChoice.KEEP);

        verify(notifier).driver(eq("driver-1"), eq("DELIVERY_PAYMENT_RECEIVED"), any(), eq(d.getId()),
                org.mockito.ArgumentMatchers.contains("Ana pagou"));
    }

    @Test
    void naoRepeteOAvisoSeJaEstavaPaga() {
        Delivery d = confirmed();
        d.setPaymentStatus(PaymentStatus.PAID);
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(pagavelRide()));
        when(deliveryService.podeVerComoRemetente(d, "sender-1")).thenReturn(true);
        when(coupons.amountToCharge(any(), anyString(), any(), anyLong())).thenReturn(2000L);
        when(payments.start(any(), anyLong(), anyString(), any(Map.class), anyString())).thenReturn(response);

        service.start(deliveryId, "sender-1", CouponChoice.KEEP);

        verify(notifier, never()).driver(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void naoIniciaPagamentoDeQuemNaoERemetenteNemDeEncomendaPendente() {
        Delivery d = confirmed();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(Fixtures.ride()));
        when(deliveryService.podeVerComoRemetente(d, "outro")).thenReturn(false);
        assertStatus(() -> service.start(deliveryId, "outro", CouponChoice.KEEP), HttpStatus.NOT_FOUND);

        Delivery pendente = Fixtures.delivery(rideId, DeliveryStatus.PENDING);
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(pendente));
        when(deliveryService.podeVerComoRemetente(pendente, "sender-1")).thenReturn(true);
        assertStatus(() -> service.start(deliveryId, "sender-1", CouponChoice.KEEP), HttpStatus.CONFLICT);

        Delivery entregue = Fixtures.delivery(rideId, DeliveryStatus.DELIVERED);
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(entregue));
        when(deliveryService.podeVerComoRemetente(entregue, "sender-1")).thenReturn(true);
        assertStatus(() -> service.start(deliveryId, "sender-1", CouponChoice.KEEP), HttpStatus.CONFLICT);
    }

    @Test
    void encomendaInexistenteOuSemCaronaDa404() {
        when(deliveries.findById(deliveryId)).thenReturn(Optional.empty());
        assertStatus(() -> service.start(deliveryId, "s", CouponChoice.KEEP), HttpStatus.NOT_FOUND);

        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(confirmed()));
        when(rides.findById(rideId)).thenReturn(Optional.empty());
        assertStatus(() -> service.start(deliveryId, "s", CouponChoice.KEEP), HttpStatus.NOT_FOUND);
    }

    @Test
    void consultaOStatusEConfereNoStripe() {
        Delivery d = confirmed();
        d.setPaymentStatus(PaymentStatus.PAID);
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(Fixtures.ride()));
        when(deliveryService.podeVerComoRemetente(d, "sender-1")).thenReturn(true);
        when(payments.refresh(d)).thenReturn(true);
        when(payments.view(eq(d), eq(2000L))).thenReturn(response);

        PaymentResponse r = service.status(deliveryId, "sender-1");

        assertThat(r).isEqualTo(response.withCoupon(d));
        verify(deliveries).save(d);
    }

    @Test
    void statusConsideraODescontoDoCupom() {
        Delivery d = confirmed();
        d.setPaymentStatus(PaymentStatus.PAID);
        d.setDiscountCents(500L);
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(Fixtures.ride()));
        when(deliveryService.podeVerComoRemetente(d, "sender-1")).thenReturn(true);
        when(payments.refresh(d)).thenReturn(false);
        when(payments.view(d, 1500L)).thenReturn(response);

        service.status(deliveryId, "sender-1");
        verify(payments).view(d, 1500L);
    }

    @Test
    void motoristaPodeConsultarOStatus() {
        Delivery d = confirmed();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(Fixtures.ride()));
        when(deliveryService.podeVerComoRemetente(d, "driver-1")).thenReturn(false);
        when(payments.refresh(d)).thenReturn(false);
        when(payments.view(eq(d), anyLong())).thenReturn(response);

        assertThat(service.status(deliveryId, "driver-1")).isNotNull();
    }

    @Test
    void estranhoNaoVeOStatus() {
        Delivery d = confirmed();
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(Fixtures.ride()));
        when(deliveryService.podeVerComoRemetente(d, "estranho")).thenReturn(false);
        assertStatus(() -> service.status(deliveryId, "estranho"), HttpStatus.NOT_FOUND);
    }

    @Test
    void webhookAtualizaAEncomendaDonaDaCobranca() {
        Delivery d = confirmed();
        PaymentIntentInfo intent = new PaymentIntentInfo("pi_1", "secret", "succeeded", 2000, 2000, Map.of());
        when(deliveries.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(d));
        when(payments.apply(d, intent)).thenReturn(true);
        when(rides.findById(rideId)).thenReturn(Optional.of(Fixtures.ride()));

        service.onPaymentIntentChanged(new PaymentIntentChanged(intent));

        verify(deliveries).save(d);
        verify(notifier).driver(eq("driver-1"), eq("DELIVERY_PAYMENT_RECEIVED"), any(), any(), anyString());
    }

    @Test
    void webhookSemMudancaDePagamentoNaoAvisa() {
        Delivery d = confirmed();
        PaymentIntentInfo intent = new PaymentIntentInfo("pi_1", "secret", "processing", 2000, 0, Map.of());
        when(deliveries.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(d));
        when(payments.apply(d, intent)).thenReturn(false);

        service.onPaymentIntentChanged(new PaymentIntentChanged(intent));

        verify(deliveries).save(d);
        verifyNoInteractions(notifier);
    }

    @Test
    void webhookDeCobrancaDesconhecidaNaoFazNada() {
        when(deliveries.findByPaymentIntentId("pi_x")).thenReturn(Optional.empty());
        service.onPaymentIntentChanged(new PaymentIntentChanged(new PaymentIntentInfo("pi_x", "secret", "succeeded", 1, 1, Map.of())));
        verifyNoInteractions(payments);
    }

    @Test
    void avisoUsaRemetenteGenericoQuandoNaoHaNome() {
        Delivery d = confirmed();
        d.setSenderName(null);
        d.setPaymentStatus(PaymentStatus.PAID);
        when(deliveries.findById(deliveryId)).thenReturn(Optional.of(d));
        when(rides.findById(rideId)).thenReturn(Optional.of(Fixtures.ride()));
        when(deliveryService.podeVerComoRemetente(d, "sender-1")).thenReturn(true);
        when(payments.refresh(d)).thenReturn(true);
        when(payments.view(eq(d), anyLong())).thenReturn(response);

        service.status(deliveryId, "sender-1");

        verify(notifier).driver(eq("driver-1"), eq("DELIVERY_PAYMENT_RECEIVED"), any(), any(),
                org.mockito.ArgumentMatchers.contains("O remetente pagou"));
    }

    private Ride pagavelRide() {
        return new Ride() {
            @Override public UUID getId() { return rideId; }
            @Override public String getOriginCity() { return "São Paulo"; }
            @Override public String getDestinationCity() { return "Campinas"; }
            @Override public String getDriverSub() { return "driver-1"; }
        };
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(status);
    }
}
