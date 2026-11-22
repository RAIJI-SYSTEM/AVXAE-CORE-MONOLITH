package com.raiji.rides.rewards;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.raiji.common.enums.PaymentStatus;
import com.raiji.rides.domain.RidePassenger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final String ME = "passageiro-1";

    private CouponRepository repository;
    private CouponService service;
    private RidePassenger booking;

    @BeforeEach
    void setUp() {
        repository = mock(CouponRepository.class);
        service = new CouponService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        booking = new RidePassenger();
        booking.setPassengerSub(ME);
    }

    private CouponRepository.Coupon coupon(long cents, Instant expires, Instant usedAt) {
        var c = new CouponRepository.Coupon(UUID.randomUUID(), ME, "RAIJIABCDE", "Teste", "", cents, "ADS",
                expires, usedAt);
        when(repository.find(c.id())).thenReturn(Optional.of(c));
        return c;
    }

    private CouponChoice use(CouponRepository.Coupon c) {
        return new CouponChoice(CouponChoice.Mode.USE, c.id());
    }

    @Test
    @DisplayName("Desconta o cupom do valor cobrado")
    void desconta() {
        var c = coupon(500, NOW.plus(Duration.ofDays(5)), null);
        assertThat(service.amountToCharge(booking, ME, use(c), 2000)).isEqualTo(1500);
        assertThat(booking.getCouponId()).isEqualTo(c.id());
        assertThat(booking.getDiscountCents()).isEqualTo(500);
    }

    @Test
    @DisplayName("Cupom maior que o preço: cobra no mínimo R$ 1,00")
    void minimo() {
        var c = coupon(1500, NOW.plus(Duration.ofDays(5)), null);
        assertThat(service.amountToCharge(booking, ME, use(c), 800)).isEqualTo(CouponService.MIN_CHARGE_CENTS);
        assertThat(booking.getDiscountCents()).isEqualTo(700);
    }

    @Test
    @DisplayName("Recusa cupom vencido, já usado ou de outra pessoa")
    void recusa() {
        var vencido = coupon(500, NOW.minusSeconds(1), null);
        assertThatThrownBy(() -> service.amountToCharge(booking, ME, use(vencido), 2000))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("venceu");
        var usado = coupon(500, NOW.plus(Duration.ofDays(5)), NOW);
        assertThatThrownBy(() -> service.amountToCharge(booking, ME, use(usado), 2000))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("já foi usado");
        var meu = coupon(500, NOW.plus(Duration.ofDays(5)), null);
        assertThatThrownBy(() -> service.amountToCharge(booking, "outra-pessoa", use(meu), 2000))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("não encontrado");
    }

    @Test
    @DisplayName("Tirar o cupom volta ao preço cheio; pago, o cupom não muda mais")
    void tirarETravar() {
        var c = coupon(500, NOW.plus(Duration.ofDays(5)), null);
        service.amountToCharge(booking, ME, use(c), 2000);
        assertThat(service.amountToCharge(booking, ME, new CouponChoice(CouponChoice.Mode.REMOVE, null), 2000)).isEqualTo(2000);

        service.amountToCharge(booking, ME, use(c), 2000);
        booking.setPaymentStatus(PaymentStatus.PAID);
        assertThat(service.amountToCharge(booking, ME, new CouponChoice(CouponChoice.Mode.REMOVE, null), 2000)).isEqualTo(1500);
    }

    @Test
    @DisplayName("Retomando com cupom que venceu: sai sozinho")
    void retomaSemCupomVencido() {
        var c = coupon(500, NOW.plus(Duration.ofDays(5)), null);
        service.amountToCharge(booking, ME, use(c), 2000);
        var vencido = new CouponRepository.Coupon(c.id(), ME, c.code(), "", "", 500, "ADS", NOW.minusSeconds(1), null);
        when(repository.find(c.id())).thenReturn(Optional.of(vencido));
        assertThat(service.amountToCharge(booking, ME, CouponChoice.KEEP, 2000)).isEqualTo(2000);
        assertThat(booking.getCouponId()).isNull();
    }

    @Test
    @DisplayName("Pago marca o cupom como usado; estornado devolve")
    void usaEDevolve() {
        var c = coupon(500, NOW.plus(Duration.ofDays(5)), null);
        service.onPaid(booking);
        verify(repository, never()).markUsed(c.id(), NOW);
        service.amountToCharge(booking, ME, use(c), 2000);
        service.onPaid(booking);
        verify(repository).markUsed(c.id(), NOW);
        service.onRefunded(booking);
        verify(repository).release(c.id());
    }

    @Test
    @DisplayName("Corpo do pagamento: sem corpo mantém, 'none' tira, id usa")
    void escolha() {
        assertThat(CouponChoice.from(null).mode()).isEqualTo(CouponChoice.Mode.KEEP);
        assertThat(CouponChoice.from(new CouponChoice.Body("none")).mode()).isEqualTo(CouponChoice.Mode.REMOVE);
        UUID id = UUID.randomUUID();
        assertThat(CouponChoice.from(new CouponChoice.Body(id.toString()))).isEqualTo(new CouponChoice(CouponChoice.Mode.USE, id));
        assertThatThrownBy(() -> CouponChoice.from(new CouponChoice.Body("xyz"))).isInstanceOf(ResponseStatusException.class);
    }
}
