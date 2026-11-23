package com.raiji.deliveries.board;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardOfferRepository extends JpaRepository<BoardOffer, UUID> {

    List<BoardOffer> findByRequestIdInAndDeletedAtIsNullOrderByPriceAsc(Collection<UUID> requestIds);

    List<BoardOffer> findByRequestIdAndStatus(UUID requestId, BoardOfferStatus status);

    boolean existsByRequestIdAndDriverSubAndStatus(UUID requestId, String driverSub, BoardOfferStatus status);

    List<BoardOffer> findByDriverSubAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
            String driverSub, Collection<BoardOfferStatus> statuses);
}
