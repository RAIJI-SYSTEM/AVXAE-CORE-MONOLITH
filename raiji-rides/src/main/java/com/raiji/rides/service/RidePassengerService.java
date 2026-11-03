package com.raiji.rides.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.dto.BaggageItemRequest;
import com.raiji.rides.dto.RideCancelResponse;
import com.raiji.rides.dto.RideDecisionResponse;
import com.raiji.rides.dto.RidePassengerResponse;
import com.raiji.rides.event.RideCancelledEvent;
import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.event.RideRequestCreatedEvent;
import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;
import com.raiji.users.domain.Vehicle;
import com.raiji.users.repository.VehicleRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RidePassengerService {

    private static final List<RidePassengerStatus> ACTIVE_STATUSES =
            List.of(
                    RidePassengerStatus.PENDING,
                    RidePassengerStatus.CONFIRMED
            );

    private final RideRepository rideRepository;
    private final RidePassengerRepository ridePassengerRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final VehicleRepository vehicleRepository;

    public RidePassengerService(
            RideRepository rideRepository,
            RidePassengerRepository ridePassengerRepository,
            ApplicationEventPublisher eventPublisher,
            VehicleRepository vehicleRepository
    ) {
        this.rideRepository = rideRepository;
        this.ridePassengerRepository = ridePassengerRepository;
        this.eventPublisher = eventPublisher;
        this.vehicleRepository = vehicleRepository;
    }

    /**
     * S2-01 / RF18: a solicitação de vaga exige a declaração de bagagem.
     * Solicitação + bagagem são persistidas na MESMA transação
     * (cascade ALL em RidePassenger.baggage).
     */
    @Transactional
    public RidePassengerResponse request(
            UUID rideId,
            String passengerSub,
            String passengerName,
            List<BaggageItemRequest> baggage
    ) {
        if (baggage == null || baggage.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A declaração de bagagem é obrigatória (mínimo 1 item)."
            );
        }

        Ride ride = rideRepository.findById(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta carona não está disponível para solicitações."
            );
        }

        if (ride.getDepartureDate() == null
                || !ride.getDepartureDate().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não é possível solicitar uma carona com partida passada."
            );
        }

        boolean alreadyRequested =
                ridePassengerRepository
                        .existsByRideIdAndPassengerSubAndStatusInAndDeletedAtIsNull(
                                rideId,
                                passengerSub,
                                ACTIVE_STATUSES
                        );

        if (alreadyRequested) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Você já possui uma solicitação ativa para esta carona."
            );
        }

        long activeRequests =
                ridePassengerRepository
                        .countByRideIdAndStatusInAndDeletedAtIsNull(
                                rideId,
                                ACTIVE_STATUSES
                        );

        if (ride.getAvailableSeats() == null
                || ride.getAvailableSeats() <= 0
                || activeRequests >= ride.getTotalSeats()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não há vagas disponíveis nesta carona."
            );
        }

        RidePassenger passenger = new RidePassenger();
        passenger.setRideId(rideId);
        passenger.setPassengerSub(passengerSub);
        passenger.setPassengerName(passengerName.trim());

        for (BaggageItemRequest item : baggage) {
            Baggage baggageEntity = new Baggage();
            baggageEntity.setType(item.type());
            baggageEntity.setQuantity(item.quantity());
            baggageEntity.setLengthCm(item.lengthCm());
            baggageEntity.setWidthCm(item.widthCm());
            baggageEntity.setHeightCm(item.heightCm());
            baggageEntity.setWeightKg(item.weightKg());
            baggageEntity.setDescription(item.description());
            passenger.addBaggage(baggageEntity);
        }

        // cascade ALL: solicitação + bagagem persistidas juntas (mesma transação)
        RidePassenger saved = ridePassengerRepository.save(passenger);

        eventPublisher.publishEvent(
                new RideRequestCreatedEvent(
                        ride.getId(),
                        ride.getDriverSub(),
                        ride.getDriverName(),
                        saved.getPassengerSub(),
                        saved.getPassengerName(),
                        saved.getJoinedAt()
                )
        );

        return RidePassengerResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<RidePassengerResponse> listForDriver(
            UUID rideId,
            String driverSub
    ) {
        Ride ride = rideRepository.findById(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não tem permissão para visualizar as solicitações desta carona."
            );
        }

        return ridePassengerRepository
                .findByRideIdAndDeletedAtIsNull(rideId)
                .stream()
                .map(RidePassengerResponse::from)
                .toList();
    }

    /**
     * S2-02 / RF08: o motorista aceita (ACCEPT) ou recusa (REJECT) a
     * solicitação após visualizar a bagagem declarada (S2-01).
     *
     * <p>ACCEPT => CONFIRMED + confirmedAt + decremento de availableSeats.
     * REJECT => CANCELLED + cancellationReason (vagas permanecem intactas).
     * Nos dois casos o passageiro é notificado via RideDecisionEvent (RF19).
     */
    @Transactional
    public RideDecisionResponse decide(
            UUID rideId,
            UUID requestId,
            String driverSub,
            RideDecision decision,
            String reason
    ) {
        if (decision == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A decisão é obrigatória (ACCEPT ou REJECT)."
            );
        }

        if (decision == RideDecision.REJECT
                && (reason == null || reason.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O motivo (reason) é obrigatório ao recusar (REJECT)."
            );
        }

        // S2-03 / RN03: SELECT ... FOR UPDATE — serializa decisões
        // concorrentes sobre a mesma carona (ver docs/ARQUITETURA.md).
        Ride ride = rideRepository.findByIdForUpdate(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não tem permissão para decidir solicitações desta carona."
            );
        }

        RidePassenger passenger = ridePassengerRepository.findById(requestId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> rideId.equals(p.getRideId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Solicitação não encontrada."
                ));

        if (passenger.getStatus() != RidePassengerStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta solicitação já foi decidida."
            );
        }

        Instant decidedAt;
        if (decision == RideDecision.ACCEPT) {
            if (ride.getAvailableSeats() == null
                    || ride.getAvailableSeats() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Não há vagas disponíveis nesta carona."
                );
            }
            // S2-03 / RN12: trava de capacidade de carga (peso + volume)
            validarCapacidadeCarga(ride, passenger);

            ride.setAvailableSeats(ride.getAvailableSeats() - 1);
            passenger.confirm();
            decidedAt = passenger.getConfirmedAt();
        } else {
            passenger.cancel(reason.trim());
            decidedAt = Instant.now();
        }

        rideRepository.save(ride);
        RidePassenger saved = ridePassengerRepository.save(passenger);

        eventPublisher.publishEvent(
                new RideDecisionEvent(
                        ride.getId(),
                        saved.getId(),
                        ride.getDriverSub(),
                        ride.getDriverName(),
                        saved.getPassengerSub(),
                        saved.getPassengerName(),
                        decision,
                        decision == RideDecision.REJECT ? reason.trim() : null,
                        decidedAt
                )
        );

        return RideDecisionResponse.from(saved);
    }

    /**
     * S2-04 / RF09 + RN06 — cancelamento de reserva ANTES da viagem.
     *
     * <p>Pode ser invocado pelo passageiro dono da reserva OU pelo motorista
     * dono da carona. Regras (critérios de aceite da issue #11):
     * <ul>
     *   <li><b>403</b> — o ator não é o passageiro da reserva nem o motorista
     *       da carona ("passageiro tentando cancelar reserva de outro").</li>
     *   <li><b>409</b> — reserva já CANCELLED ("reserva já cancelada").</li>
     *   <li><b>409</b> — reserva COMPLETED (não é PENDING/CONFIRMED).</li>
     *   <li><b>409</b> — carona não está PLANNED ("após início da viagem").</li>
     *   <li>CONFIRMED =&gt; {@code availableSeats++} na hora (libera a vaga);
     *       PENDING =&gt; vagas permanecem intactas.</li>
     * </ul>
     *
     * <p>Assim como o {@link #decide}, usa lock pessimista na carona
     * ({@code findByIdForUpdate}) para que a devolução da vaga seja
     * consistente sob concorrência (S2-03 / RN03). Publica
     * {@link RideCancelledEvent} para notificar o motorista.
     */
    @Transactional
    public RideCancelResponse cancel(
            UUID rideId,
            UUID requestId,
            String actorSub,
            String reason
    ) {
        Ride ride = rideRepository.findByIdForUpdate(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        RidePassenger passenger = ridePassengerRepository.findById(requestId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> rideId.equals(p.getRideId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Solicitação não encontrada."
                ));

        boolean donoDaReserva = passenger.getPassengerSub().equals(actorSub);
        boolean motoristaDaCarona = ride.getDriverSub().equals(actorSub);
        if (!donoDaReserva && !motoristaDaCarona) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não tem permissão para cancelar esta reserva."
            );
        }

        if (passenger.getStatus() == RidePassengerStatus.CANCELLED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta reserva já está cancelada."
            );
        }

        if (passenger.getStatus() != RidePassengerStatus.PENDING
                && passenger.getStatus() != RidePassengerStatus.CONFIRMED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta reserva não pode ser cancelada no status "
                            + passenger.getStatus() + "."
            );
        }

        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não é possível cancelar a reserva após o início da viagem."
            );
        }

        boolean seatReleased =
                passenger.getStatus() == RidePassengerStatus.CONFIRMED;

        passenger.cancel(reason == null ? null : reason.trim());

        if (seatReleased) {
            int atuais = ride.getAvailableSeats() == null
                    ? 0 : ride.getAvailableSeats();
            int total = ride.getTotalSeats() == null
                    ? atuais + 1 : ride.getTotalSeats();
            // nunca ultrapassa total_seats (CHECK chk_seats_consistency)
            ride.setAvailableSeats(Math.min(atuais + 1, total));
        }

        rideRepository.save(ride);
        RidePassenger saved = ridePassengerRepository.save(passenger);

        eventPublisher.publishEvent(
                new RideCancelledEvent(
                        ride.getId(),
                        saved.getId(),
                        ride.getDriverSub(),
                        ride.getDriverName(),
                        saved.getPassengerSub(),
                        saved.getPassengerName(),
                        saved.getCancellationReason(),
                        saved.getCancelledAt(),
                        seatReleased
                )
        );

        return RideCancelResponse.from(saved, seatReleased);
    }

    /**
     * S2-03 / RN12 — trava de capacidade de carga no ACCEPT.
     *
     * <p>Soma o peso (weightKg × quantity) e o volume
     * (lengthCm × widthCm × heightCm × quantity ÷ 1000, em litros) das
     * bagagens CONFIRMED na carona + as da solicitação em aceite, e compara
     * com cargoDetails.maxWeightKg (carona) e cargoVolumeLiters (veículo),
     * quando declarados. Excedeu => 400; sem limite declarado => ignora
     * ("se existir", critério da issue #10).
     */
    private void validarCapacidadeCarga(Ride ride, RidePassenger candidato) {
        List<RidePassenger> passageiros = ridePassengerRepository
                .findByRideIdAndDeletedAtIsNull(ride.getId());

        BigDecimal pesoTotal = BigDecimal.ZERO;
        BigDecimal volumeTotalLitros = BigDecimal.ZERO;

        for (RidePassenger p : passageiros) {
            boolean confirmado =
                    p.getStatus() == RidePassengerStatus.CONFIRMED;
            boolean ehCandidato = p.getId() != null
                    && p.getId().equals(candidato.getId());
            if (!confirmado && !ehCandidato) {
                continue; // PENDING não reserva capacidade
            }
            for (Baggage item : p.getBaggage()) {
                pesoTotal = pesoTotal.add(pesoTotalDoItem(item));
                volumeTotalLitros =
                        volumeTotalLitros.add(volumeDoItemEmLitros(item));
            }
        }

        CargoDetails cargo = ride.getCargoDetails();
        if (cargo != null && cargo.hasCargoCapacity()
                && pesoTotal.compareTo(cargo.getMaxWeightKg()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Peso total das bagagens ("
                            + pesoTotal.stripTrailingZeros().toPlainString()
                            + " kg) excede o limite da carona ("
                            + cargo.getMaxWeightKg().stripTrailingZeros().toPlainString()
                            + " kg)."
            );
        }

        Vehicle vehicle = vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(
                        ride.getVehicleId(), ride.getDriverSub())
                .orElse(null);

        if (vehicle != null
                && vehicle.getCargoVolumeLiters() != null
                && vehicle.getCargoVolumeLiters() > 0
                && volumeTotalLitros.compareTo(
                        BigDecimal.valueOf(vehicle.getCargoVolumeLiters())) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Volume total das bagagens ("
                            + volumeTotalLitros.stripTrailingZeros().toPlainString()
                            + " L) excede a capacidade do veículo ("
                            + vehicle.getCargoVolumeLiters() + " L)."
            );
        }
    }

    /** Peso do item = weightKg × quantity; sem weightKg declarado => 0. */
    private static BigDecimal pesoTotalDoItem(Baggage item) {
        if (item.getWeightKg() == null) {
            return BigDecimal.ZERO;
        }
        return item.getWeightKg()
                .multiply(BigDecimal.valueOf(item.getQuantity()));
    }

    /** Volume do item = (lengthCm × widthCm × heightCm ÷ 1000) × quantity, em litros. */
    private static BigDecimal volumeDoItemEmLitros(Baggage item) {
        BigDecimal volumeUnitarioLitros = BigDecimal
                .valueOf((long) item.getLengthCm()
                        * item.getWidthCm()
                        * item.getHeightCm())
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        return volumeUnitarioLitros
                .multiply(BigDecimal.valueOf(item.getQuantity()));
    }
}
