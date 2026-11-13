package com.raiji.rides.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Ride;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideRepository extends JpaRepository<Ride, UUID>, JpaSpecificationExecutor<Ride> {

    /**
     * S2-03 / RN03 — trava pessimista ({@code SELECT ... FOR UPDATE}) na linha
     * da carona: serializa decisões concorrentes. Dois aceites simultâneos
     * sobre a última vaga => um confirma e o outro, ao reler a carona após o
     * commit do primeiro, recebe 409. Estratégia documentada em
     * docs/ARQUITETURA.md (o {@code @Version} herdado de AuditableEntity
     * permanece como rede de segurança).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Ride r where r.id = :id")
    Optional<Ride> findByIdForUpdate(@Param("id") UUID id);

    /**
     * S2-06 / RF10 + RN05 — caronas PLANNED cujo {@code departureDate} já foi
     * atingido ({@code <= cutoff}). Usada pelo {@code RideStatusScheduler} para
     * promover a {@code IN_PROGRESS}. O filtro por {@code PLANNED} torna a
     * execução idempotente (uma carona já promovida não aparece de novo).
     */
    List<Ride> findByStatusAndDepartureDateLessThanEqualAndDeletedAtIsNull(
            RideStatus status, LocalDateTime cutoff);

    /**
     * Busca pelo CAMINHO da viagem (PostGIS): a rota passa a até {@code radius} metros
     * do embarque e, com destino, também do desembarque — e o desembarque vem DEPOIS do
     * embarque no sentido da viagem. Cobre "perto de mim" (só origem), "de A para B"
     * e "pegar no meio do caminho" (A e B são pontos da rota, não só as pontas).
     */
    @Query(value = """
            WITH o AS (SELECT ST_SetSRID(ST_MakePoint(:oLng, :oLat), 4326) AS g),
                 d AS (SELECT ST_SetSRID(ST_MakePoint(:dLng, :dLat), 4326) AS g)
            SELECT r.id AS "id",
                   ST_LineLocatePoint(r.route::geometry, o.g) AS "pickupFraction",
                   CASE WHEN :hasDest THEN ST_LineLocatePoint(r.route::geometry, d.g) END AS "dropoffFraction",
                   ST_Distance(r.route, o.g::geography) AS "pickupDistance",
                   CASE WHEN :hasDest THEN ST_Distance(r.route, d.g::geography) END AS "dropoffDistance"
            FROM rides r, o, d
            WHERE r.deleted_at IS NULL
              AND r.status = 'PLANNED'
              AND r.bookings_closed = FALSE
              AND r.visibility = 'PUBLIC'
              AND r.route IS NOT NULL
              AND r.departure_date >= :fromDate
              AND r.departure_date < :toDate
              AND r.ride_type IN (:types)
              AND (:needSeat = FALSE OR r.available_seats > 0)
              AND r.driver_sub <> :requesterSub
              AND ST_DWithin(r.route, o.g::geography, :radius)
              AND (:hasDest = FALSE OR (
                    ST_DWithin(r.route, d.g::geography, :radius)
                    AND ST_LineLocatePoint(r.route::geometry, d.g) > ST_LineLocatePoint(r.route::geometry, o.g)))
            ORDER BY r.departure_date, "pickupDistance"
            LIMIT 50
            """, nativeQuery = true)
    List<RideRouteMatch> searchAlongRoute(
            @Param("oLat") double originLatitude,
            @Param("oLng") double originLongitude,
            @Param("hasDest") boolean hasDestination,
            @Param("dLat") double destinationLatitude,
            @Param("dLng") double destinationLongitude,
            @Param("radius") double radiusMeters,
            @Param("fromDate") LocalDateTime from,
            @Param("toDate") LocalDateTime to,
            @Param("types") List<String> rideTypes,
            @Param("needSeat") boolean needSeat,
            @Param("requesterSub") String requesterSub);

    /** Onde dois pontos caem na rota de UMA carona (para o preço do trecho de um pedido). */
    @Query(value = """
            WITH o AS (SELECT ST_SetSRID(ST_MakePoint(:oLng, :oLat), 4326) AS g),
                 d AS (SELECT ST_SetSRID(ST_MakePoint(:dLng, :dLat), 4326) AS g)
            SELECT r.id AS "id",
                   ST_LineLocatePoint(r.route::geometry, o.g) AS "pickupFraction",
                   ST_LineLocatePoint(r.route::geometry, d.g) AS "dropoffFraction",
                   ST_Distance(r.route, o.g::geography) AS "pickupDistance",
                   ST_Distance(r.route, d.g::geography) AS "dropoffDistance"
            FROM rides r, o, d
            WHERE r.id = :rideId AND r.route IS NOT NULL
            """, nativeQuery = true)
    Optional<RideRouteMatch> locateOnRoute(
            @Param("rideId") UUID rideId,
            @Param("oLat") double pickupLatitude,
            @Param("oLng") double pickupLongitude,
            @Param("dLat") double dropoffLatitude,
            @Param("dLng") double dropoffLongitude);

    /** Caronas do motorista (as mais próximas da partida primeiro). */
    List<Ride> findByDriverSubAndStatusInAndDeletedAtIsNullOrderByDepartureDateAsc(
            String driverSub, List<RideStatus> statuses);

    /** O motorista tem carona em algum destes status? (ex.: PLANNED, IN_PROGRESS) */
    boolean existsByDriverSubAndStatusInAndDeletedAtIsNull(
            String driverSub, List<RideStatus> statuses);
}
