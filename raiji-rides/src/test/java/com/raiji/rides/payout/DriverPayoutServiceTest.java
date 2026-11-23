package com.raiji.rides.payout;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverPayoutServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
    private PayoutRepository repository;
    private ApplicationEventPublisher events;
    private DriverPayoutService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(PayoutRepository.class);
        events = mock(ApplicationEventPublisher.class);
        ObjectProvider<Clock> clock = mock(ObjectProvider.class);
        when(clock.getIfAvailable(any())).thenReturn(CLOCK);
        service = new DriverPayoutService(repository, events, clock);
    }

    private static PayoutItem item(String driver, long paid, String pct) {
        long fee = PayoutMath.feeCents(paid, new BigDecimal(pct));
        return new PayoutItem("RIDE", UUID.randomUUID(), UUID.randomUUID(), driver, "Ana", "x",
                LocalDateTime.of(2026, 11, 20, 8, 0), Instant.now(), paid, fee, paid - fee);
    }

    @Test
    @DisplayName("comissão: arredonda ao centavo como o PriceBreakdown; 0% fica tudo com o motorista")
    void math() {
        assertThat(PayoutMath.feeCents(1000, new BigDecimal("1.5"))).isEqualTo(15);
        assertThat(PayoutMath.feeCents(333, new BigDecimal("1.5"))).isEqualTo(5); // 4,995 -> 5
        assertThat(PayoutMath.driverShareCents(1000, new BigDecimal("1.5"))).isEqualTo(985);
        assertThat(PayoutMath.driverShareCents(1000, BigDecimal.ZERO)).isEqualTo(1000);
    }

    @Test
    @DisplayName("saldos: agrupa por motorista com a chave Pix, maior saldo primeiro")
    void balances() {
        when(repository.pendingItems(null, false)).thenReturn(List.of(
                item("a", 1000, "1.5"), item("b", 5000, "1.5"), item("a", 1000, "1.5")));
        when(repository.pixKeys()).thenReturn(java.util.Map.of("b", "b@pix"));

        var balances = service.balances();

        assertThat(balances).extracting(DriverPayoutService.DriverBalance::driverSub).containsExactly("b", "a");
        assertThat(balances.get(0).pixKey()).isEqualTo("b@pix");
        assertThat(balances.get(1).amountCents()).isEqualTo(1970);
        assertThat(balances.get(1).itemCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("registrar repasse: grava, liga os itens e avisa o motorista")
    void registra() {
        List<PayoutItem> items = List.of(item("a", 1000, "1.5"), item("a", 2000, "1.5"));
        when(repository.pendingItems("a", true)).thenReturn(items);
        when(repository.pixKey("a")).thenReturn(Optional.of("a@pix"));

        DriverPayout payout = service.registerPayout("a", 2955, " pix 123 ", "admin-1");

        assertThat(payout.amountCents()).isEqualTo(2955);
        assertThat(payout.itemCount()).isEqualTo(2);
        assertThat(payout.pixKey()).isEqualTo("a@pix");
        assertThat(payout.note()).isEqualTo("pix 123");
        verify(repository).insertPayout(payout, "admin-1");
        verify(repository).markPaidOut(payout.id(), items);
        verify(events).publishEvent(any(com.raiji.common.notification.NotificationRequested.class));
    }

    @Test
    @DisplayName("registrar repasse: valor diferente do saldo atual ou nada a receber -> 409, nada gravado")
    void conflito() {
        when(repository.pendingItems("a", true)).thenReturn(List.of(item("a", 1000, "1.5")));
        assertThatThrownBy(() -> service.registerPayout("a", 1000, null, "adm"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("mudou");

        when(repository.pendingItems("b", true)).thenReturn(List.of());
        assertThatThrownBy(() -> service.registerPayout("b", 0, null, "adm"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("nada a receber");

        verify(repository, never()).insertPayout(any(), any());
        verify(repository, never()).markPaidOut(any(), anyList());
    }

    @Test
    @DisplayName("chave Pix: obrigatória e com tamanho limite")
    void pix() {
        assertThat(service.savePixKey("a", "  a@pix.com ")).isEqualTo("a@pix.com");
        verify(repository).savePixKey(eq("a"), eq("a@pix.com"));
        assertThatThrownBy(() -> service.savePixKey("a", " ")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.savePixKey("a", "x".repeat(141))).isInstanceOf(ResponseStatusException.class);
    }
}
