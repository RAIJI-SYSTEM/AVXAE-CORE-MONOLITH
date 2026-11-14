package com.raiji.rides.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.notification.NotificationRequested;
import com.raiji.rides.cargo.CargoCapacityService;
import com.raiji.rides.cargo.CargoLoad;
import com.raiji.rides.dto.AcceptAllResponse;
import com.raiji.rides.dto.MyBookingResponse;
import com.raiji.rides.dto.RideResponse;
import com.raiji.rides.geo.GeoSupport;
import com.raiji.rides.geo.Place;
import com.raiji.rides.pricing.SegmentPricing;
import com.raiji.rides.repository.RideRouteMatch;
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

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RidePassengerService {

    private static final Logger log =
            LoggerFactory.getLogger(RidePassengerService.class);

    private static final List<RidePassengerStatus> ACTIVE_STATUSES =
            List.of(
                    RidePassengerStatus.PENDING,
                    RidePassengerStatus.CONFIRMED
            );

    private final RideRepository rideRepository;
    private final RidePassengerRepository ridePassengerRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CargoCapacityService cargoCapacity;
    private final RideRefundService rideRefundService;
    private final Clock clock;

    /** Distância máxima (m) entre a rota e o embarque/desembarque pedido. */
    static final double MAX_DETOUR_METERS = 5_000;

    public RidePassengerService(
            RideRepository rideRepository,
            RidePassengerRepository ridePassengerRepository,
            ApplicationEventPublisher eventPublisher,
            CargoCapacityService cargoCapacity,
            RideRefundService rideRefundService,
            Clock clock
    ) {
        this.rideRepository = rideRepository;
        this.ridePassengerRepository = ridePassengerRepository;
        this.eventPublisher = eventPublisher;
        this.cargoCapacity = cargoCapacity;
        this.rideRefundService = rideRefundService;
        this.clock = clock;
    }

    @Transactional
    public RidePassengerResponse request(
            UUID rideId,
            String passengerSub,
            String passengerName,
            List<BaggageItemRequest> baggage
    ) {
        return request(rideId, passengerSub, passengerName, baggage, null, null);
    }

    /**
     * Pedido de vaga. A bagagem é OPCIONAL (vazia = sem bagagem; dá para editar
     * depois). Com pickup/dropoff, a pessoa embarca/desce no meio do caminho e
     * paga o proporcional do trecho. Pedido + bagagem na MESMA transação.
     */
    @Transactional
    public RidePassengerResponse request(
            UUID rideId,
            String passengerSub,
            String passengerName,
            List<BaggageItemRequest> baggage,
            Place pickup,
            Place dropoff
    ) {
        Ride ride = rideRepository.findById(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        // A mesma conta pode ser motorista e passageira — mas não na mesma carona
        if (ride.getDriverSub().equals(passengerSub)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Você não pode pedir vaga na sua própria carona."
            );
        }

        if (!ride.carriesPassengers()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta viagem leva só encomendas (sem vagas para passageiros)."
            );
        }

        if (ride.getStatus() != RideStatus.PLANNED || ride.isBookingsClosed()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta carona não está disponível para solicitações."
            );
        }

        if (ride.getDepartureDate() == null
                || !ride.getDepartureDate().isAfter(LocalDateTime.now(clock))) {
            // S2-06 / RF10: bloqueia solicitações a partir do horário de saída
            // (departureDate <= now => 409), mesmo antes do scheduler promover
            // a carona para IN_PROGRESS.
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
        aplicarTrecho(ride, passenger, pickup, dropoff);

        for (BaggageItemRequest item : baggage == null ? List.<BaggageItemRequest>of() : baggage) {
            cargoCapacity.assertItemDimensions(ride, item.lengthCm(), item.widthCm(), item.heightCm());
            passenger.addBaggage(toEntity(item, false));
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
        return decide(rideId, requestId, driverSub, decision, reason, null);
    }

    /**
     * S2-02 / RF08 (+ S2-05 / RN04): variante que também recebe o
     * {@code paymentIntentId} da carona paga. No ACCEPT, o id é gravado na
     * reserva para viabilizar o estorno automático no cancelamento.
     */
    @Transactional
    public RideDecisionResponse decide(
            UUID rideId,
            UUID requestId,
            String driverSub,
            RideDecision decision,
            String reason,
            String paymentIntentId
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

        // S2-06 / RF10: bloqueia o aceite/recusa quando a carona já não está
        // PLANNED (partida atingida / viagem em andamento) => 409.
        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não é possível decidir solicitações de uma carona que já iniciou."
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
            cargoCapacity.assertFits(ride,
                    CargoCapacityService.loadOf(passenger.activeBaggage()), passenger.getId());

            // S2-05 / RN04: vincula o PaymentIntent (carona paga) à reserva
            // para permitir o estorno automático num cancelamento futuro.
            if (paymentIntentId != null && !paymentIntentId.isBlank()) {
                passenger.setPaymentIntentId(paymentIntentId.trim());
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

        // S2-05 / RN04: estorno automático de carona paga (nunca impede o
        // cancelamento — falhas viram registro ESTORNO_FALHOU na auditoria).
        try {
            rideRefundService.refundIfApplicable(ride, saved, actorSub);
        } catch (RuntimeException ex) {
            log.warn(
                    "Estorno não processado para a reserva {}: {}",
                    saved.getId(), ex.getMessage());
        }

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

    // ------------------------------------------------------------------ aceitar todos

    /**
     * "Aceitar todos": confirma, na ordem de chegada, os pedidos PENDENTES de
     * viagem inteira enquanto houver vaga e espaço para a bagagem. Pedidos de
     * trecho ficam para o motorista decidir um a um.
     */
    @Transactional
    public AcceptAllResponse acceptAll(UUID rideId, String driverSub) {
        Ride ride = rideDoMotoristaParaDecidir(rideId, driverSub);

        List<RidePassenger> pendentes = ridePassengerRepository.findByRideIdAndDeletedAtIsNull(rideId).stream()
                .filter(p -> p.getStatus() == RidePassengerStatus.PENDING)
                .sorted(Comparator.comparing(RidePassenger::getJoinedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<UUID> aceitos = new ArrayList<>();
        List<AcceptAllResponse.Skipped> pulados = new ArrayList<>();
        for (RidePassenger p : pendentes) {
            if (!p.isFullRoute()) {
                pulados.add(new AcceptAllResponse.Skipped(p.getId(), "Pedido de trecho: decida individualmente."));
                continue;
            }
            if (ride.getAvailableSeats() == null || ride.getAvailableSeats() <= 0) {
                pulados.add(new AcceptAllResponse.Skipped(p.getId(), "Não há mais vagas."));
                continue;
            }
            try {
                cargoCapacity.assertFits(ride, CargoCapacityService.loadOf(p.activeBaggage()), p.getId());
            } catch (ResponseStatusException e) {
                pulados.add(new AcceptAllResponse.Skipped(p.getId(), e.getReason()));
                continue;
            }
            ride.setAvailableSeats(ride.getAvailableSeats() - 1);
            p.confirm();
            ridePassengerRepository.save(p);
            // salva a cada aceite: a próxima checagem de capacidade enxerga este passageiro
            ridePassengerRepository.flush();
            aceitos.add(p.getId());
            publicarDecisao(ride, p, RideDecision.ACCEPT, null, p.getConfirmedAt());
        }
        rideRepository.save(ride);
        return new AcceptAllResponse(aceitos, pulados, ride.getAvailableSeats());
    }

    // ------------------------------------------------------------- editar bagagem

    /**
     * O passageiro troca a bagagem (lista vazia = sem bagagem) até a viagem começar.
     * Pedido pendente: vale na hora. Pedido aceito: se a carga não aumenta (peso,
     * volume e número de volumes), vale na hora; se aumenta, fica esperando o
     * motorista aprovar e a vaga continua garantida com a bagagem anterior.
     */
    @Transactional
    public RidePassengerResponse updateBaggage(UUID rideId, UUID requestId, String passengerSub,
                                               List<BaggageItemRequest> items) {
        Ride ride = rideRepository.findByIdForUpdate(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carona não encontrada."));
        RidePassenger passenger = pedidoDaCarona(rideId, requestId);
        if (!passenger.getPassengerSub().equals(passengerSub)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este pedido é de outra pessoa.");
        }
        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A viagem já começou: a bagagem não pode mais mudar.");
        }
        if (passenger.getStatus() != RidePassengerStatus.PENDING
                && passenger.getStatus() != RidePassengerStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este pedido não está mais ativo.");
        }
        for (BaggageItemRequest item : items) {
            cargoCapacity.assertItemDimensions(ride, item.lengthCm(), item.widthCm(), item.heightCm());
        }

        List<Baggage> novos = items.stream().map(i -> toEntity(i, false)).toList();
        boolean precisaAprovar = passenger.getStatus() == RidePassengerStatus.CONFIRMED
                && aumentaCarga(passenger.activeBaggage(), novos);

        if (precisaAprovar) {
            passenger.getBaggage().removeIf(Baggage::isPendingApproval);
            novos.forEach(b -> {
                b.setPendingApproval(true);
                passenger.addBaggage(b);
            });
            eventPublisher.publishEvent(new NotificationRequested(ride.getDriverSub(), "DRIVER",
                    "BAGGAGE_CHANGE_REQUEST", ride.getId(), passenger.getId(),
                    passenger.getPassengerName() + " quer levar mais bagagem na sua carona. Aprove ou recuse."));
        } else {
            passenger.getBaggage().clear();
            novos.forEach(passenger::addBaggage);
        }
        return RidePassengerResponse.from(ridePassengerRepository.save(passenger));
    }

    /** O motorista aprova (confere o espaço) ou recusa a bagagem nova de um passageiro aceito. */
    @Transactional
    public RidePassengerResponse decideBaggageChange(UUID rideId, UUID requestId, String driverSub, boolean approve) {
        Ride ride = rideDoMotoristaParaDecidir(rideId, driverSub);
        RidePassenger passenger = pedidoDaCarona(rideId, requestId);
        List<Baggage> pendentes = passenger.pendingBaggage();
        if (pendentes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Não há alteração de bagagem esperando aprovação.");
        }
        if (approve) {
            cargoCapacity.assertFits(ride, CargoCapacityService.loadOf(pendentes), passenger.getId());
            passenger.getBaggage().removeIf(b -> !b.isPendingApproval());
            passenger.getBaggage().forEach(b -> b.setPendingApproval(false));
        } else {
            passenger.getBaggage().removeIf(Baggage::isPendingApproval);
        }
        eventPublisher.publishEvent(new NotificationRequested(passenger.getPassengerSub(), "PASSENGER",
                approve ? "BAGGAGE_CHANGE_APPROVED" : "BAGGAGE_CHANGE_REJECTED", ride.getId(), passenger.getId(),
                approve ? "O motorista aprovou a sua bagagem nova."
                        : "O motorista não aprovou a bagagem nova: vale a bagagem anterior."));
        return RidePassengerResponse.from(ridePassengerRepository.save(passenger));
    }

    // ------------------------------------------------------------- ciclo de vida

    /** Recusa todos os pedidos pendentes (reservas encerradas, viagem iniciada ou cancelada). */
    @Transactional
    public int rejectAllPending(Ride ride, String reason) {
        int total = 0;
        for (RidePassenger p : ridePassengerRepository.findByRideIdAndDeletedAtIsNull(ride.getId())) {
            if (p.getStatus() != RidePassengerStatus.PENDING) continue;
            p.cancel(reason);
            ridePassengerRepository.save(p);
            publicarDecisao(ride, p, RideDecision.REJECT, reason, Instant.now());
            total++;
        }
        return total;
    }

    /** Viagem cancelada pelo motorista: cancela as reservas aceitas (com estorno) e avisa. */
    @Transactional
    public int cancelAllConfirmed(Ride ride, String reason, String actorSub) {
        int total = 0;
        for (RidePassenger p : ridePassengerRepository.findByRideIdAndDeletedAtIsNull(ride.getId())) {
            if (p.getStatus() != RidePassengerStatus.CONFIRMED) continue;
            p.cancel(reason);
            RidePassenger saved = ridePassengerRepository.save(p);
            try {
                rideRefundService.refundIfApplicable(ride, saved, actorSub);
            } catch (RuntimeException ex) {
                log.warn("Estorno não processado para a reserva {}: {}", saved.getId(), ex.getMessage());
            }
            eventPublisher.publishEvent(new NotificationRequested(p.getPassengerSub(), "PASSENGER",
                    "RIDE_CANCELLED", ride.getId(), p.getId(),
                    "A carona com " + ride.getDriverName() + " foi cancelada pelo motorista."
                            + (reason == null || reason.isBlank() ? "" : " Motivo: " + reason)));
            total++;
        }
        return total;
    }

    /** Viagem finalizada: as reservas aceitas viram concluídas. */
    @Transactional
    public int completeAllConfirmed(Ride ride) {
        int total = 0;
        for (RidePassenger p : ridePassengerRepository.findByRideIdAndDeletedAtIsNull(ride.getId())) {
            if (p.getStatus() != RidePassengerStatus.CONFIRMED) continue;
            p.complete();
            ridePassengerRepository.save(p);
            total++;
        }
        return total;
    }

    /** "Minhas viagens" do passageiro (ativas primeiro; depois o histórico). */
    @Transactional(readOnly = true)
    public List<MyBookingResponse> myBookings(String passengerSub) {
        List<RidePassenger> pedidos = ridePassengerRepository.findByPassengerSub(passengerSub).stream()
                .filter(p -> p.getDeletedAt() == null).toList();
        Map<UUID, Ride> rides = rideRepository.findAllById(pedidos.stream().map(RidePassenger::getRideId).distinct().toList())
                .stream().collect(Collectors.toMap(Ride::getId, r -> r));
        return pedidos.stream()
                .filter(p -> rides.containsKey(p.getRideId()))
                .sorted(Comparator.comparing((RidePassenger p) -> rides.get(p.getRideId()).getDepartureDate()).reversed())
                .map(p -> new MyBookingResponse(RidePassengerResponse.from(p), RideResponse.from(rides.get(p.getRideId()))))
                .toList();
    }

    // ------------------------------------------------------------------ apoio

    private Ride rideDoMotoristaParaDecidir(UUID rideId, String driverSub) {
        Ride ride = rideRepository.findByIdForUpdate(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carona não encontrada."));
        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Você não tem permissão para decidir solicitações desta carona.");
        }
        if (ride.getStatus() != RideStatus.PLANNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não é possível decidir solicitações de uma carona que já iniciou.");
        }
        return ride;
    }

    private RidePassenger pedidoDaCarona(UUID rideId, UUID requestId) {
        return ridePassengerRepository.findById(requestId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> rideId.equals(p.getRideId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitação não encontrada."));
    }

    private void publicarDecisao(Ride ride, RidePassenger p, RideDecision decision, String reason, Instant at) {
        eventPublisher.publishEvent(new RideDecisionEvent(ride.getId(), p.getId(), ride.getDriverSub(),
                ride.getDriverName(), p.getPassengerSub(), p.getPassengerName(), decision, reason, at));
    }

    /** Embarque/desembarque no meio do caminho: confere a rota e calcula o preço do trecho. */
    private void aplicarTrecho(Ride ride, RidePassenger passenger, Place pickup, Place dropoff) {
        passenger.setFullRoute(true);
        passenger.setPrice(ride.getPricePerPassenger());
        if (pickup == null && dropoff == null) {
            return;
        }
        if (ride.getRoute() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta carona não tem rota no mapa: não dá para embarcar ou descer no meio do caminho.");
        }
        double pLat = pickup != null ? pickup.latitude() : ride.getOriginLocation().getY();
        double pLng = pickup != null ? pickup.longitude() : ride.getOriginLocation().getX();
        double dLat = dropoff != null ? dropoff.latitude() : ride.getDestinationLocation().getY();
        double dLng = dropoff != null ? dropoff.longitude() : ride.getDestinationLocation().getX();
        RideRouteMatch m = rideRepository.locateOnRoute(ride.getId(), pLat, pLng, dLat, dLng)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rota da carona indisponível."));
        if (m.getPickupDistance() > MAX_DETOUR_METERS || m.getDropoffDistance() > MAX_DETOUR_METERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O embarque ou o desembarque fica longe demais do caminho desta carona (máximo 5 km).");
        }
        if (m.getDropoffFraction() <= m.getPickupFraction()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O desembarque precisa vir depois do embarque, no sentido da viagem.");
        }
        double fraction = SegmentPricing.fraction(m.getPickupFraction(), m.getDropoffFraction());
        passenger.setFullRoute(SegmentPricing.isFullRoute(fraction));
        passenger.setPrice(SegmentPricing.price(ride.getPricePerPassenger(), fraction));
        if (pickup != null) {
            passenger.setPickupLocation(GeoSupport.point(pickup.latitude(), pickup.longitude()));
            passenger.setPickupAddress(pickup.address());
        }
        if (dropoff != null) {
            passenger.setDropoffLocation(GeoSupport.point(dropoff.latitude(), dropoff.longitude()));
            passenger.setDropoffAddress(dropoff.address());
        }
    }

    private static Baggage toEntity(BaggageItemRequest item, boolean pending) {
        Baggage b = new Baggage();
        b.setType(item.type());
        b.setQuantity(item.quantity());
        b.setLengthCm(item.lengthCm());
        b.setWidthCm(item.widthCm());
        b.setHeightCm(item.heightCm());
        b.setWeightKg(item.weightKg());
        b.setDescription(item.description());
        b.setPendingApproval(pending);
        return b;
    }

    /** A bagagem nova aumenta a carga? (mais peso, mais volume ou mais volumes) */
    static boolean aumentaCarga(List<Baggage> atual, List<Baggage> nova) {
        int qtdAtual = atual.stream().mapToInt(Baggage::getQuantity).sum();
        int qtdNova = nova.stream().mapToInt(Baggage::getQuantity).sum();
        CargoLoad cargaAtual = CargoCapacityService.loadOf(atual);
        CargoLoad cargaNova = CargoCapacityService.loadOf(nova);
        return qtdNova > qtdAtual || !cargaNova.notGreaterThan(cargaAtual);
    }
}
