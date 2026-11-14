package com.raiji.rides.repository;

import com.raiji.rides.domain.RideIntent;
import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentType;
import com.raiji.rides.domain.enums.RideIntentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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
          -- "Na hora": sem horário, ou horário até 60 min à frente. Um horário que JÁ
          -- passou continua valendo enquanto a intenção não expira (antes: BETWEEN NOW()
          -- e NOW()+60min, e a intenção sumia da busca logo depois do horário desejado).
          AND (
                ri.desired_at IS NULL
                OR ri.desired_at <= NOW() + INTERVAL '60 minutes'
          )
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
        LIMIT 50
        """, nativeQuery = true)
    List<RideIntent> findNearby(
            @Param("type") String type,
            @Param("mode") String mode,
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("radiusMeters") double radiusMeters
    );

    // Mural: intenções dentro de um intervalo futuro.
    @Query(value = """
        SELECT ri.*
        FROM ride_intents ri
        WHERE ri.status = 'ACTIVE'
          AND ri.expires_at > NOW()
          AND ri.type = :type
          AND ri.mode = :mode
          AND ri.desired_at >= :startAt
          AND ri.desired_at < :endAt
          AND ri.origin_location IS NOT NULL
          AND ST_DWithin(
                ri.origin_location,
                ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                :radiusMeters
          )
        ORDER BY ri.desired_at ASC,
                 ST_Distance(
                    ri.origin_location,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
                 )
        LIMIT 100
        """, nativeQuery = true)
    List<RideIntent> findBoard(
            @Param("type") String type,
            @Param("mode") String mode,
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("radiusMeters") double radiusMeters,
            @Param("startAt") OffsetDateTime startAt,
            @Param("endAt") OffsetDateTime endAt
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
