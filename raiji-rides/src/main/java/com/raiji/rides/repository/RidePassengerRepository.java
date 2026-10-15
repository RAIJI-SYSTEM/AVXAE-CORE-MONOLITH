package com.raiji.rides.repository;

import java.util.List;
import java.util.UUID;

import com.raiji.rides.domain.RidePassenger;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RidePassengerRepository extends JpaRepository<RidePassenger, UUID> {
    List<RidePassenger> findByRideId(UUID rideId);
    List<RidePassenger> findByPassengerSub(String passengerSub);
}
