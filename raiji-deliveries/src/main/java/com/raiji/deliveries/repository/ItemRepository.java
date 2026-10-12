package com.raiji.deliveries.repository;

import java.util.List;
import java.util.UUID;

import com.raiji.deliveries.domain.Item;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, UUID> {
    List<Item> findByDeliveryId(UUID deliveryId);
}
