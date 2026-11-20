package com.raiji.rides.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.rides.domain.RidePassenger;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RidePassengerRepository extends JpaRepository<RidePassenger, UUID> {

    List<RidePassenger> findByRideId(UUID rideId);

    List<RidePassenger> findByPassengerSub(String passengerSub);

    long countByRideIdAndStatusInAndDeletedAtIsNull(
            UUID rideId,
            Collection<RidePassengerStatus> statuses
    );

    boolean existsByRideIdAndPassengerSubAndStatusInAndDeletedAtIsNull(
            UUID rideId,
            String passengerSub,
            Collection<RidePassengerStatus> statuses
    );

    List<RidePassenger> findByRideIdAndDeletedAtIsNull(UUID rideId);

    /** Reserva dona da cobrança (webhook do Stripe). */
    Optional<RidePassenger> findByPaymentIntentId(String paymentIntentId);
}