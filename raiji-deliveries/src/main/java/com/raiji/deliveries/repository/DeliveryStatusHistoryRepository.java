package com.raiji.deliveries.repository;

import java.util.List;
import java.util.UUID;

import com.raiji.deliveries.DeliveryStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryStatusHistoryRepository
        extends JpaRepository<DeliveryStatusHistory, UUID> {

    List<DeliveryStatusHistory> findByDeliveryIdOrderByCreatedAtAsc(
            UUID deliveryId
    );
}