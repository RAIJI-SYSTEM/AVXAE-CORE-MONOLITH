package com.raiji.rides.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.repository.RideRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S2-06 / RF10 + RN05 — testes unitários do {@link RideStatusScheduler} com
 * {@link Clock} mockado (fixo) e repositório mockado.
 *
 * <p>Cobre: promoção de PLANNED vencidas, o limite exato
 * {@code departureDate == now()} (incluso), caronas futuras não afetadas e
 * idempotência (2ª execução não reprocessa).
 */
@DisplayName("RideStatusScheduler — status automático (S2-06/RN05)")
class RideStatusSchedulerTest {

    private RideRepository rideRepository;
    private RideStatusScheduler scheduler;

    /** Relógio fixo em 2026-06-01T10:00:00Z. */
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        rideRepository = mock(RideRepository.class);

        Instant instant = LocalDateTime.of(2026, 6, 1, 10, 0, 0)
                .toInstant(ZoneOffset.UTC);
        Clock fixed = Clock.fixed(instant, ZoneOffset.UTC);
        now = LocalDateTime.now(fixed); // == 2026-06-01T10:00

        scheduler = new RideStatusScheduler(rideRepository, fixed);
    }

    private Ride ride(LocalDateTime departure, RideStatus status) {
        Ride r = new Ride();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setDepartureDate(departure);
        r.setStatus(status);
        return r;
    }

    private void stubConsulta(List<Ride> resultado) {
        when(rideRepository
                .findByStatusAndDepartureDateLessThanEqualAndDeletedAtIsNull(
                        RideStatus.PLANNED, now))
                .thenReturn(resultado);
    }

    @Test
    @DisplayName("promove PLANNED com partida vencida e no limite exato (== now)")
    void devePromoverVencidasENoLimiteExato() {
        Ride vencida = ride(now.minusMinutes(1), RideStatus.PLANNED);
        Ride noLimite = ride(now, RideStatus.PLANNED); // departureDate == now
        stubConsulta(List.of(vencida, noLimite));

        int promovidas = scheduler.promoteDueRides();

        assertEquals(2, promovidas);
        assertEquals(RideStatus.IN_PROGRESS, vencida.getStatus());
        assertEquals(RideStatus.IN_PROGRESS, noLimite.getStatus(),
                "o limite exato departureDate == now() deve ser promovido (<=)");
        verify(rideRepository).saveAll(List.of(vencida, noLimite));
    }

    @Test
    @DisplayName("usa cutoff = now(clock) na consulta (relógio injetável)")
    void deveUsarCutoffDoClock() {
        stubConsulta(List.of());

        scheduler.promoteDueRides();

        verify(rideRepository)
                .findByStatusAndDepartureDateLessThanEqualAndDeletedAtIsNull(
                        RideStatus.PLANNED, now);
    }

    @Test
    @DisplayName("nada a promover => não salva (caronas futuras não são afetadas)")
    void naoDevePromoverQuandoNadaVence() {
        stubConsulta(List.of());

        int promovidas = scheduler.promoteDueRides();

        assertEquals(0, promovidas);
        verify(rideRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("idempotente: 2ª execução não reprocessa carona já IN_PROGRESS")
    void deveSerIdempotente() {
        Ride vencida = ride(now.minusMinutes(5), RideStatus.PLANNED);
        when(rideRepository
                .findByStatusAndDepartureDateLessThanEqualAndDeletedAtIsNull(
                        RideStatus.PLANNED, now))
                .thenReturn(List.of(vencida))  // 1ª execução: ainda PLANNED
                .thenReturn(List.of());        // 2ª: a consulta filtra PLANNED

        assertEquals(1, scheduler.promoteDueRides());
        assertEquals(RideStatus.IN_PROGRESS, vencida.getStatus());

        assertEquals(0, scheduler.promoteDueRides(),
                "nenhuma carona nova na 2ª execução");
        verify(rideRepository).saveAll(any());
    }
}
