package com.raiji.rides.repository;

import java.util.UUID;

import com.raiji.rides.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository
        extends JpaRepository<Notification, UUID> {
}