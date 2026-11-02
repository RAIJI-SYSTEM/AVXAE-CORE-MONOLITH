package com.raiji.rides.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.dto.BaggageItemRequest;
import com.raiji.rides.dto.RideDecisionResponse;
import com.raiji.rides.dto.RidePassengerResponse;
import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.event.RideRequestCreatedEvent;
import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;

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

    public RidePassengerService(
            RideRepository rideRepository,
            RidePassengerRepository ridePassengerRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.rideRepository = rideRepository;
        this.ridePassengerRepository = ridePassengerRepository;
        this.eventPublisher = eventPublisher;
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

        Ride ride = rideRepository.findById(rideId)
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
}
