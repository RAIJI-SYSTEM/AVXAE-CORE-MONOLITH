package com.raiji.deliveries.board;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardRequestRepository extends JpaRepository<BoardRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BoardRequest b where b.id = :id and b.deletedAt is null")
    Optional<BoardRequest> findByIdForUpdate(@Param("id") UUID id);

    @Query("select b from BoardRequest b where b.deletedAt is null and (b.requesterSub = :sub or b.companyId in :companyIds) "
            + "order by b.createdAt desc")
    List<BoardRequest> findMine(@Param("sub") String sub, @Param("companyIds") Collection<UUID> companyIds);

    /**
     * Mural de um motorista: pedidos abertos com a saída perto dele ({@code radius}) OU que uma carona
     * publicada dele atende (as duas pontas a até {@code detour} da rota, no sentido da viagem e em
     * horário compatível: de 1h antes a 4h depois do pedido).
     */
    @Query(value = """
            SELECT m.id AS "id", m.distance AS "distance", m.ride_id AS "rideId" FROM (
                SELECT b.id, b.desired_at,
                       CASE WHEN :hasPos THEN ST_Distance(b.origin_location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) END AS distance,
                       (SELECT r.id FROM rides r
                         WHERE r.driver_sub = :driverSub AND r.deleted_at IS NULL AND r.status = 'PLANNED'
                           AND r.bookings_closed = FALSE AND r.visibility = 'PUBLIC' AND r.route IS NOT NULL
                           AND r.departure_date > :now
                           AND r.departure_date BETWEEN b.desired_at - INTERVAL '1 hour' AND b.desired_at + INTERVAL '4 hours'
                           AND ((b.kind = 'SEAT' AND r.ride_type IN ('PASSENGER', 'MIXED') AND r.available_seats > 0)
                             OR (b.kind = 'PACKAGE' AND r.ride_type IN ('CARGO', 'MIXED')))
                           AND ST_DWithin(r.route, b.origin_location, :detour)
                           AND ST_DWithin(r.route, b.destination_location, :detour)
                           AND ST_LineLocatePoint(r.route::geometry, b.destination_location::geometry)
                             > ST_LineLocatePoint(r.route::geometry, b.origin_location::geometry)
                         ORDER BY ABS(EXTRACT(EPOCH FROM (r.departure_date - b.desired_at))) LIMIT 1) AS ride_id
                  FROM board_requests b
                 WHERE b.deleted_at IS NULL AND b.status = 'OPEN' AND b.desired_at > :now
                   AND b.requester_sub <> :driverSub
            ) m
            WHERE m.distance <= :radius OR m.ride_id IS NOT NULL
            ORDER BY m.desired_at LIMIT 100
            """, nativeQuery = true)
    List<BoardMatch> findForDriver(@Param("driverSub") String driverSub, @Param("hasPos") boolean hasPosition,
                                   @Param("lat") double latitude, @Param("lng") double longitude,
                                   @Param("radius") double radiusMeters, @Param("detour") double detourMeters,
                                   @Param("now") LocalDateTime now);
}
