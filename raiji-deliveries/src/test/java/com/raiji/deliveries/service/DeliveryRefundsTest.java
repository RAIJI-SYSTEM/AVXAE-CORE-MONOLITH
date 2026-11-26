package com.raiji.deliveries.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import com.raiji.deliveries.domain.Delivery;
import com.raiji.rides.service.RideRefundService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.raiji.deliveries.support.Fixtures;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryRefundsTest {

    @Mock RideRefundService refunds;
    @InjectMocks DeliveryRefunds deliveryRefunds;

    @Test
    void delegaParaAAuditoriaDeEstornosComOsDadosDaEncomenda() {
        Delivery d = Fixtures.delivery();
        d.setPrice(new BigDecimal("20.00"));

        deliveryRefunds.refundIfPaid(d, "actor");

        verify(refunds).refundIfPaid(eq(d), eq(DeliveryRefunds.ENTITY_TYPE), eq(d.getId()), eq("actor"),
                eq(2000L), eq("rideId=" + d.getRideId()));
    }

    @Test
    void precoNuloViraZeroCentavos() {
        Delivery d = Fixtures.delivery();
        d.setPrice(null);
        deliveryRefunds.refundIfPaid(d, null);
        verify(refunds).refundIfPaid(eq(d), anyString(), eq(d.getId()), isNull(), eq(0L), anyString());
    }

    @Test
    void tipoDeEntidadeEUsadoNaAuditoria() {
        assertThat(DeliveryRefunds.ENTITY_TYPE).isEqualTo("DELIVERY");
    }
}
