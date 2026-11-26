package com.raiji.deliveries.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.raiji.deliveries.domain.PackageDetails;
import com.raiji.deliveries.domain.PackageSize;
import com.raiji.deliveries.dto.PackageRequest;
import com.raiji.rides.cargo.CargoLoad;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PackagesTest {

    @Test
    void montaOPacoteUsandoAsMedidasDoTamanhoQuandoNaoInformadas() {
        PackageDetails p = Packages.from(new PackageRequest(PackageSize.SMALL, "  Livro  ", new BigDecimal("1.00"),
                null, null, null, true, new BigDecimal("30.00"), "  Bia  ", " +5511999999999 "));

        assertThat(p.getSize()).isEqualTo(PackageSize.SMALL);
        assertThat(p.getDescription()).isEqualTo("Livro");
        assertThat(p.getLengthCm()).isEqualTo(30);
        assertThat(p.getWidthCm()).isEqualTo(20);
        assertThat(p.getHeightCm()).isEqualTo(15);
        assertThat(p.isFragile()).isTrue();
        assertThat(p.getDeclaredValue()).isEqualByComparingTo("30.00");
        assertThat(p.getRecipientName()).isEqualTo("Bia");
        assertThat(p.getRecipientPhone()).isEqualTo("+5511999999999");
    }

    @Test
    void respeitaAsMedidasInformadas() {
        PackageDetails p = Packages.from(new PackageRequest(PackageSize.LARGE, "Caixa", new BigDecimal("10"),
                90, 70, 60, false, null, "Bia", "11999999999"));
        assertThat(p.getLengthCm()).isEqualTo(90);
        assertThat(p.getWidthCm()).isEqualTo(70);
        assertThat(p.getHeightCm()).isEqualTo(60);
    }

    @Test
    void recusaPesoAcimaDoMaximoDoTamanho() {
        assertThatThrownBy(() -> Packages.from(new PackageRequest(PackageSize.ENVELOPE, "Doc", new BigDecimal("2"),
                null, null, null, false, null, "Bia", "11999999999")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("O peso passa do máximo");
    }

    @Test
    void converteEmCargaParaOCalculoDeCapacidade() {
        PackageDetails p = Packages.from(new PackageRequest(PackageSize.MEDIUM, "Caixa", new BigDecimal("3.50"),
                null, null, null, false, null, "Bia", "11999999999"));
        CargoLoad load = Packages.load(p);
        assertThat(load).isEqualTo(CargoLoad.of(50, 40, 30, 1, new BigDecimal("3.50")));
    }
}
