package com.raiji.deliveries.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.raiji.common.enums.DeliveryStatus;

import org.junit.jupiter.api.Test;

class DeliveryDomainTest {

    @Test
    void percorreTodoOCicloDeVida() {
        Delivery d = new Delivery();
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(d.active()).isTrue();

        d.confirm();
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.CONFIRMED);
        assertThat(d.getConfirmedAt()).isNotNull();
        assertThat(d.active()).isTrue();

        d.pickUp();
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        assertThat(d.getPickedUpAt()).isNotNull();
        assertThat(d.getFailedCodeAttempts()).isZero();
        assertThat(d.active()).isTrue();

        d.deliver();
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(d.getDeliveredAt()).isNotNull();
        assertThat(d.active()).isFalse();
    }

    @Test
    void cancelamentoGuardaMotivo() {
        Delivery d = new Delivery();
        d.cancel("  endereço errado  ");
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(d.getCancelledAt()).isNotNull();
        assertThat(d.getCancellationReason()).isEqualTo("  endereço errado  ");
        assertThat(d.active()).isFalse();
    }

    @Test
    void coletaZeraTentativasErradas() {
        Delivery d = new Delivery();
        d.setFailedCodeAttempts(4);
        d.pickUp();
        assertThat(d.getFailedCodeAttempts()).isZero();
    }

    @Test
    void packageDetailsCopiaTodosOsCampos() {
        PackageDetails p = new PackageDetails();
        p.setSize(PackageSize.LARGE);
        p.setDescription("Móvel");
        p.setWeightKg(new BigDecimal("25.00"));
        p.setLengthCm(80);
        p.setWidthCm(60);
        p.setHeightCm(50);
        p.setFragile(true);
        p.setDeclaredValue(new BigDecimal("500.00"));
        p.setRecipientName("Bia");
        p.setRecipientPhone("+5511999999999");

        PackageDetails c = p.copy();

        assertThat(c).usingRecursiveComparison().isEqualTo(p);
        assertThat(c).isNotSameAs(p);
    }

    @Test
    void tamanhosTemMedidasEPesoMaximo() {
        assertThat(PackageSize.ENVELOPE.maxWeightKg).isEqualByComparingTo("1");
        assertThat(PackageSize.SMALL.lengthCm).isEqualTo(30);
        assertThat(PackageSize.MEDIUM.widthCm).isEqualTo(40);
        assertThat(PackageSize.LARGE.heightCm).isEqualTo(50);
        assertThat(PackageSize.LARGE.maxWeightKg).isEqualByComparingTo("30");
    }
}
