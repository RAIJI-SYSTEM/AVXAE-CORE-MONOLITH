package com.raiji.deliveries.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.Delivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    Page<Delivery> findBySenderSubAndDeletedAtIsNullOrderByRequestedAtDesc(
            String senderSub,
            Pageable pageable
    );

    List<Delivery> findByRideIdAndDeletedAtIsNull(UUID rideId);

    List<Delivery> findByRideIdAndStatusInAndDeletedAtIsNull(
            UUID rideId,
            Collection<DeliveryStatus> statuses
    );

    List<Delivery> findByStatusAndRideIdIsNullAndDeletedAtIsNullOrderByRequestedAtDesc(
            DeliveryStatus status
    );

    List<Delivery> findByVehicleIdAndStatusInAndDeletedAtIsNull(
            UUID vehicleId,
            Collection<DeliveryStatus> statuses
    );

    boolean existsByRideIdAndSenderSubAndStatusInAndDeletedAtIsNull(
            UUID rideId,
            String senderSub,
            Collection<DeliveryStatus> statuses
    );

    boolean existsByTrackingCode(String trackingCode);

    /**
     * Minhas encomendas: as que eu enviei e as das empresas de que participo.
     */
    @Query("""
            select d from Delivery d
            where d.deletedAt is null
              and (d.senderSub = :sub or d.companyId in :companyIds)
            order by d.requestedAt desc
            """)
    List<Delivery> findMine(
            @Param("sub") String sub,
            @Param("companyIds") Collection<UUID> companyIds
    );

    @Query("""
            select d from Delivery d
            where d.deletedAt is null
              and d.rideId in :rideIds
            order by d.requestedAt desc
            """)
    List<Delivery> findByRideIds(
            @Param("rideIds") Collection<UUID> rideIds
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Histórico paginado do motorista, com filtros opcionais.
     */
    @Query(
            value = """
                    select d from Delivery d
                    where d.deletedAt is null
                      and d.driverSub = :driverSub
                      and (:status is null or d.status = :status)
                      and d.requestedAt >= coalesce(:startDate, d.requestedAt)
                      and d.requestedAt <= coalesce(:endDate, d.requestedAt)
                    """,
            countQuery = """
                    select count(d) from Delivery d
                    where d.deletedAt is null
                      and d.driverSub = :driverSub
                      and (:status is null or d.status = :status)
                      and d.requestedAt >= coalesce(:startDate, d.requestedAt)
                      and d.requestedAt <= coalesce(:endDate, d.requestedAt)
                    """
    )
    Page<Delivery> findDriverHistory(
            @Param("driverSub") String driverSub,
            @Param("status") DeliveryStatus status,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            Pageable pageable
    );

    /**
     * Histórico paginado do remetente e das empresas de que participa.
     */
    @Query(
            value = """
                    select d from Delivery d
                    where d.deletedAt is null
                      and (d.senderSub = :senderSub or d.companyId in :companyIds)
                      and (:status is null or d.status = :status)
                      and d.requestedAt >= coalesce(:startDate, d.requestedAt)
                      and d.requestedAt <= coalesce(:endDate, d.requestedAt)
                    """,
            countQuery = """
                    select count(d) from Delivery d
                    where d.deletedAt is null
                      and (d.senderSub = :senderSub or d.companyId in :companyIds)
                      and (:status is null or d.status = :status)
                      and d.requestedAt >= coalesce(:startDate, d.requestedAt)
                      and d.requestedAt <= coalesce(:endDate, d.requestedAt)
                    """
    )
    Page<Delivery> findSenderHistory(
            @Param("senderSub") String senderSub,
            @Param("companyIds") Collection<UUID> companyIds,
            @Param("status") DeliveryStatus status,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            Pageable pageable
    );
}
