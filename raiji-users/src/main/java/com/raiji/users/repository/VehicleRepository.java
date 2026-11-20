package com.raiji.users.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.raiji.users.domain.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    List<Vehicle> findByOwnerSubAndDeletedAtIsNull(String ownerSub);

    Optional<Vehicle> findByIdAndOwnerSubAndDeletedAtIsNull(
            UUID id,
            String ownerSub
    );

    boolean existsByPlate(String plate);

    boolean existsByPlateAndIdNot(String plate,UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Vehicle v WHERE v.id = :id")
    Optional<Vehicle> findByIdForUpdate(@Param("id") UUID id);
}
