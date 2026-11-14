package com.raiji.deliveries.freight;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FreightRequestRepository extends JpaRepository<FreightRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FreightRequest f where f.id = :id")
    Optional<FreightRequest> findByIdForUpdate(@Param("id") UUID id);

    List<FreightRequest> findByCompanyIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID companyId);

    List<FreightRequest> findByTargetDriverSubAndStatusAndDeletedAtIsNullOrderByDesiredDateAsc(
            String targetDriverSub, FreightStatus status);

    /** Pedidos públicos em aberto com a origem perto do motorista. */
    @Query(value = """
            SELECT f.* FROM freight_requests f
            WHERE f.deleted_at IS NULL AND f.status = 'OPEN' AND f.target_driver_sub IS NULL
              AND f.desired_date > :now
              AND ST_DWithin(f.origin_location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)
            ORDER BY f.desired_date
            LIMIT 50
            """, nativeQuery = true)
    List<FreightRequest> findOpenNearby(@Param("lat") double latitude, @Param("lng") double longitude,
                                        @Param("radius") double radiusMeters, @Param("now") LocalDateTime now);
}
