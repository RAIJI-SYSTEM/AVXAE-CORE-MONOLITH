package com.raiji.deliveries.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.common.enums.PaymentStatus;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.deliveries.dto.DeliveryResponse;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.geo.GeoSupport;
import org.junit.jupiter.api.Test;

import com.raiji.deliveries.support.Fixtures;

class DeliveryResponseTest {

    @Test
    void paraORemetenteMostraOsCodigosEOEnderecoExato() {
        Delivery d = Fixtures.delivery();
        d.setPickupLocation(GeoSupport.point(-23.55, -46.63));
        d.setPickupAddress("Rua A");
        d.setDropoffLocation(GeoSupport.point(-22.90, -47.06));
        d.setDropoffAddress("Rua B");
        d.setFullRoute(false);
        Ride ride = Fixtures.ride();

        DeliveryResponse r = DeliveryResponse.of(d, ride, true);

        assertThat(r.id()).isEqualTo(d.getId());
        assertThat(r.pickupCode()).isEqualTo("1234");
        assertThat(r.deliveryKeyword()).isEqualTo("SOL-42");
        assertThat(r.pickupLatitude()).isEqualTo(-23.55);
        assertThat(r.pickupLongitude()).isEqualTo(-46.63);
        assertThat(r.dropoffAddress()).isEqualTo("Rua B");
        assertThat(r.fullRoute()).isFalse();
        assertThat(r.price()).isEqualByComparingTo("20.00");
        assertThat(r.platformFee()).isEqualByComparingTo("3.00");
        assertThat(r.driverReceives()).isEqualByComparingTo("17.00");
        assertThat(r.ride()).isNotNull();
        assertThat(r.ride().driverName()).isEqualTo("Carlos");
        assertThat(r.ride().status()).isEqualTo("PLANNED");
        assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
    }

    @Test
    void paraOMotoristaEscondeOsCodigos() {
        Delivery d = Fixtures.delivery();
        DeliveryResponse r = DeliveryResponse.of(d, Fixtures.ride(), false);
        assertThat(r.pickupCode()).isNull();
        assertThat(r.deliveryKeyword()).isNull();
    }

    @Test
    void semCaronaESemLocalizacaoNaoQuebra() {
        Delivery d = Fixtures.delivery();
        DeliveryResponse r = DeliveryResponse.of(d, null, false);
        assertThat(r.ride()).isNull();
        assertThat(r.pickupLatitude()).isNull();
        assertThat(r.dropoffLatitude()).isNull();
        assertThat(r.pickupAddress()).isNull();
    }

    @Test
    void encomendaGratuitaFicaNotRequired() {
        Delivery d = Fixtures.delivery();
        d.setPrice(null);
        DeliveryResponse r = DeliveryResponse.of(d, Fixtures.ride(), true);
        assertThat(r.price()).isNull();
        assertThat(r.platformFee()).isNull();
        assertThat(r.driverReceives()).isNull();
        assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.NOT_REQUIRED);
    }

    @Test
    void encomendaPagaMostraStatusEPagamento() {
        Delivery d = Fixtures.delivery();
        d.setPaymentStatus(PaymentStatus.PAID);
        d.setPaidAt(Instant.parse("2026-01-01T10:00:00Z"));
        DeliveryResponse r = DeliveryResponse.of(d, Fixtures.ride(), true);
        assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(r.paidAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(r.status()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void resumoDaCaronaUsaOsDadosDaViagem() {
        Ride ride = Fixtures.ride();
        DeliveryResponse.RideSummary summary = DeliveryResponse.RideSummary.of(ride);
        assertThat(summary.originCity()).isEqualTo("São Paulo");
        assertThat(summary.destinationState()).isEqualTo("SP");
        assertThat(summary.meetingPoint()).isEqualTo("Posto");
        assertThat(DeliveryResponse.RideSummary.of(null)).isNull();
    }

    @Test
    void camposDoPacoteSaoCopiados() {
        Delivery d = Fixtures.delivery();
        d.getPkg().setFragile(true);
        d.getPkg().setDeclaredValue(new BigDecimal("99.90"));
        DeliveryResponse r = DeliveryResponse.of(d, null, true);
        assertThat(r.size()).isEqualTo(d.getPkg().getSize());
        assertThat(r.fragile()).isTrue();
        assertThat(r.declaredValue()).isEqualByComparingTo("99.90");
        assertThat(r.recipientName()).isEqualTo("Bia");
    }
}
