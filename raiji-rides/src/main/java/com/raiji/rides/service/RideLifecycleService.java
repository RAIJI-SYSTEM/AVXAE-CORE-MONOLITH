package com.raiji.rides.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.cargo.RidePackageBookings;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.repository.RideRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ciclo de vida da carona, pelo motorista:
 * <pre>
 * PLANNED --encerrar reservas--> PLANNED (sem pedidos novos; pendentes recusados)
 * PLANNED --iniciar (até 1h antes da partida)--> IN_PROGRESS (pendentes recusados)
 * PLANNED --(horário de partida, scheduler)--> IN_PROGRESS (pendentes recusados)
 * IN_PROGRESS --finalizar--> COMPLETED (reservas aceitas viram concluídas)
 * PLANNED --cancelar--> CANCELLED (reservas canceladas com estorno; encomendas não coletadas canceladas)
 * </pre>
 */
@Service
public class RideLifecycleService {

    /** O motorista pode iniciar a viagem até este tanto antes da partida. */
    static final int START_EARLY_MINUTES = 60;

    static final String MOTIVO_ENCERRADAS = "O motorista encerrou as reservas desta carona.";
    static final String MOTIVO_INICIADA = "A viagem já começou.";

    private final RideRepository rideRepository;
    private final RidePassengerService passengers;
    private final List<RidePackageBookings> packages;
    private final Clock clock;

    public RideLifecycleService(RideRepository rideRepository, RidePassengerService passengers,
                                List<RidePackageBookings> packages, Clock clock) {
        this.rideRepository = rideRepository;
        this.passengers = passengers;
        this.packages = packages;
        this.clock = clock;
    }

    @Transactional
    public RideResponse closeBookings(UUID rideId, String driverSub) {
        Ride ride = rideDoMotorista(rideId, driverSub);
        exigirStatus(ride, RideStatus.PLANNED, "Só dá para encerrar reservas antes da viagem começar.");
        ride.setBookingsClosed(true);
        rejeitarPendentes(ride, MOTIVO_ENCERRADAS);
        return RideResponse.from(rideRepository.save(ride));
    }

    @Transactional
    public RideResponse start(UUID rideId, String driverSub) {
        Ride ride = rideDoMotorista(rideId, driverSub);
        exigirStatus(ride, RideStatus.PLANNED, "Esta viagem não pode ser iniciada (status " + ride.getStatus() + ").");
        LocalDateTime agora = LocalDateTime.now(clock);
        if (ride.getDepartureDate().isAfter(agora.plusMinutes(START_EARLY_MINUTES))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dá para iniciar a viagem só a partir de " + START_EARLY_MINUTES + " minutos antes da partida.");
        }
        iniciar(ride);
        return RideResponse.from(rideRepository.save(ride));
    }

    /** Usado também pelo scheduler no horário de partida. */
    public void iniciar(Ride ride) {
        ride.setStatus(RideStatus.IN_PROGRESS);
        ride.setStartedAt(Instant.now(clock));
        rejeitarPendentes(ride, MOTIVO_INICIADA);
    }

    @Transactional
    public RideResponse finish(UUID rideId, String driverSub) {
        Ride ride = rideDoMotorista(rideId, driverSub);
        exigirStatus(ride, RideStatus.IN_PROGRESS, "Só dá para finalizar uma viagem em andamento.");
        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(Instant.now(clock));
        passengers.completeAllConfirmed(ride);
        return RideResponse.from(rideRepository.save(ride));
    }

    @Transactional
    public RideResponse cancel(UUID rideId, String driverSub, String reason) {
        Ride ride = rideDoMotorista(rideId, driverSub);
        exigirStatus(ride, RideStatus.PLANNED, "Só dá para cancelar uma viagem que ainda não começou.");
        String motivo = reason == null || reason.isBlank() ? "Viagem cancelada pelo motorista." : reason.trim();
        ride.setStatus(RideStatus.CANCELLED);
        ride.setCancelledAt(Instant.now(clock));
        ride.setCancellationReason(motivo);
        passengers.rejectAllPending(ride, motivo);
        passengers.cancelAllConfirmed(ride, motivo, driverSub);
        packages.forEach(p -> {
            p.rejectPending(ride.getId(), motivo);
            p.cancelNotPickedUp(ride.getId(), motivo);
        });
        return RideResponse.from(rideRepository.save(ride));
    }

    private void rejeitarPendentes(Ride ride, String motivo) {
        passengers.rejectAllPending(ride, motivo);
        packages.forEach(p -> p.rejectPending(ride.getId(), motivo));
    }

    private Ride rideDoMotorista(UUID rideId, String driverSub) {
        Ride ride = rideRepository.findByIdForUpdate(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carona não encontrada."));
        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Esta carona é de outro motorista.");
        }
        return ride;
    }

    private static void exigirStatus(Ride ride, RideStatus esperado, String mensagem) {
        if (ride.getStatus() != esperado) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, mensagem);
        }
    }
}
