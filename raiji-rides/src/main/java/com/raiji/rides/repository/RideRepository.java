package com.raiji.rides.repository;

import java.util.UUID;

import com.raiji.rides.domain.Ride;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRepository extends JpaRepository<Ride, UUID> {
}
