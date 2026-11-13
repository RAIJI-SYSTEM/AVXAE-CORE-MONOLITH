package com.raiji.deliveries.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.DeliveryStatus;
import com.raiji.deliveries.domain.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    List<Delivery> findByRideIdAndDeletedAtIsNull(UUID rideId);

    List<Delivery> findByRideIdAndStatusInAndDeletedAtIsNull(UUID rideId, Collection<DeliveryStatus> statuses);

    boolean existsByRideIdAndSenderSubAndStatusInAndDeletedAtIsNull(
            UUID rideId, String senderSub, Collection<DeliveryStatus> statuses);

    /** Minhas encomendas: as que eu enviei e as das empresas de que participo. */
    @Query("select d from Delivery d where d.deletedAt is null and "
            + "(d.senderSub = :sub or d.companyId in :companyIds) order by d.requestedAt desc")
    List<Delivery> findMine(@Param("sub") String sub, @Param("companyIds") Collection<UUID> companyIds);

    @Query("select d from Delivery d where d.deletedAt is null and d.rideId in :rideIds order by d.requestedAt desc")
    List<Delivery> findByRideIds(@Param("rideIds") Collection<UUID> rideIds);
}
