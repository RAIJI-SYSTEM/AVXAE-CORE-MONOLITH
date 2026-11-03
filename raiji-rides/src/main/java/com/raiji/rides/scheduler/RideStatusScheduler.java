package com.raiji.rides.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.repository.RideRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * S2-06 / RF10 + RN05 — ao atingir o {@code departureDate}, promove caronas
 * {@code PLANNED} para {@code IN_PROGRESS} (bloqueando novas solicitações e
 * aceites, que passam a responder 409 no {@code RidePassengerService}).
 *
 * <p>Roda a cada 60 s (fixedDelay padrão de {@code 60000} ms, configurável por
 * {@code raiji.scheduler.*} — nos testes usamos um intervalo enorme para o job
 * não interferir). É <b>idempotente</b>: a consulta filtra {@code status =
 * PLANNED}, então uma carona já promovida nunca é processada de novo.
 *
 * <p>O {@link Clock} é injetável para testes determinísticos do limite
 * {@code departureDate <= now()}.
 */
@Component
public class RideStatusScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(RideStatusScheduler.class);

    private final RideRepository rideRepository;
    private final Clock clock;

    public RideStatusScheduler(RideRepository rideRepository, Clock clock) {
        this.rideRepository = rideRepository;
        this.clock = clock;
    }

    /**
     * Promove caronas PLANNED cujo {@code departureDate <= now(clock)}.
     *
     * @return número de caronas promovidas nesta execução (0 = idempotente)
     */
    @Scheduled(
            initialDelayString = "${raiji.scheduler.initial-delay:5000}",
            fixedDelayString = "${raiji.scheduler.fixed-delay:60000}"
    )
    @Transactional
    public int promoteDueRides() {
        LocalDateTime now = LocalDateTime.now(clock);

        List<Ride> due = rideRepository
                .findByStatusAndDepartureDateLessThanEqualAndDeletedAtIsNull(
                        RideStatus.PLANNED, now);

        if (due.isEmpty()) {
            return 0;
        }

        for (Ride ride : due) {
            ride.setStatus(RideStatus.IN_PROGRESS);
        }
        rideRepository.saveAll(due);

        log.info(
                "RideStatusScheduler: {} carona(s) PLANNED -> IN_PROGRESS (cutoff {})",
                due.size(), now);

        return due.size();
    }
}
