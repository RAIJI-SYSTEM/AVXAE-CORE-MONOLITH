package com.raiji.deliveries.repository;

import java.util.Optional;
import java.util.UUID;

import com.raiji.deliveries.domain.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {
    Optional<Delivery> findByTrackingCode(String trackingCode);
}
