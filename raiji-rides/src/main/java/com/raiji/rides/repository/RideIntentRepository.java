
package com.raiji.rides.repository;

import com.raiji.rides.domain.RideIntent;
import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentType;
import com.raiji.rides.domain.enums.RideIntentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RideIntentRepository extends JpaRepository<RideIntent, UUID> {

    @Query(value = """
        SELECT ri.*
        FROM ride_intents ri
        WHERE ri.status = 'ACTIVE'
        AND ri.expires_at > NOW()
        AND ri.type = :type
          AND ri.mode = :mode
          AND ri.origin_location IS NOT NULL
          AND ST_DWithin(
                ri.origin_location,
                ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                :radiusMeters
          )
        ORDER BY ST_Distance(
            ri.origin_location,
            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
        )
        """, nativeQuery = true)
    List<RideIntent> findNearby(
            @Param("type") String type,
            @Param("mode") String mode,
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("radiusMeters") double radiusMeters
    );

    List<RideIntent> findByUserSubAndStatus(
            String userSub,
            RideIntentStatus status
    );

    List<RideIntent> findByTypeAndModeAndStatus(
            RideIntentType type,
            RideIntentMode mode,
            RideIntentStatus status
    );
}
