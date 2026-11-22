package com.raiji.rides.cargo;

import java.util.UUID;
import java.time.Instant;

/**
 * Porta para as ENCOMENDAS que viajam numa carona (implementada pelo módulo de
 * encomendas). O módulo de caronas não conhece encomendas: só pergunta a carga
 * e avisa os momentos do ciclo de vida da viagem.
 */
public interface RidePackageBookings {

    /** Carga das encomendas aceitas (ainda não entregues) na carona. */
    CargoLoad confirmedLoad(UUID rideId);

    /** Reservas encerradas / viagem iniciada: recusa os pedidos de envio pendentes. */
    void rejectPending(UUID rideId, String reason);

    /** Viagem cancelada pelo motorista: cancela as encomendas ainda não coletadas. */
    void cancelNotPickedUp(UUID rideId, String reason);

    /** Início da carona: coloca em trânsito as encomendas já confirmadas. */
    void startConfirmed(UUID rideId, Instant startedAt);

    /** Há encomendas ativas (pendentes, aceitas ou em trânsito)? */
    boolean hasActive(UUID rideId);
}
