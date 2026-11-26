package com.raiji.deliveries.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeliveryCodesTest {

    @Test
    void codigoDeColetaTemQuatroDigitos() {
        for (int i = 0; i < 50; i++) {
            assertThat(DeliveryCodes.pickupCode()).matches("\\d{4}");
        }
    }

    @Test
    void palavraChaveTemPalavraENumero() {
        for (int i = 0; i < 50; i++) {
            assertThat(DeliveryCodes.keyword()).matches("[A-Z]+-\\d{2}");
        }
    }

    @Test
    void comparacaoIgnoraCaixaEspacosEHifen() {
        assertThat(DeliveryCodes.matches("SOL-42", "sol 42")).isTrue();
        assertThat(DeliveryCodes.matches("SOL-42", "SOL42")).isTrue();
        assertThat(DeliveryCodes.matches("1234", " 1234 ")).isTrue();
        assertThat(DeliveryCodes.matches("1234", "1235")).isFalse();
    }

    @Test
    void valoresNulosNaoCombinam() {
        assertThat(DeliveryCodes.matches(null, "1234")).isFalse();
        assertThat(DeliveryCodes.matches("1234", null)).isFalse();
    }

    @Test
    void limiteDeTentativasEBaixoOMaisQueSuficiente() {
        assertThat(DeliveryCodes.MAX_FAILED_ATTEMPTS).isEqualTo(5);
    }
}
