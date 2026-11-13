package com.raiji.deliveries.freight;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FreightOfferRepository extends JpaRepository<FreightOffer, UUID> {

    List<FreightOffer> findByRequestIdAndDeletedAtIsNullOrderByPriceAsc(UUID requestId);

    List<FreightOffer> findByRequestIdAndStatus(UUID requestId, OfferStatus status);

    boolean existsByRequestIdAndDriverSubAndStatus(UUID requestId, String driverSub, OfferStatus status);

    List<FreightOffer> findByDriverSubAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
            String driverSub, Collection<OfferStatus> statuses);

    long countByRequestIdAndStatus(UUID requestId, OfferStatus status);
}
