package com.raiji.users.repository;

import java.util.List;
import java.util.UUID;

import com.raiji.users.domain.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    List<Vehicle> findByOwnerSub(String ownerSub);
    boolean existsByPlate(String plate);
}
