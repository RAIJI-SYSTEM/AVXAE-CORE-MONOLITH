package com.raiji.rides.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.raiji.rides.domain.Notification;
import com.raiji.rides.domain.NotificationAudience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository
        extends JpaRepository<Notification, UUID> {

    List<Notification> findTop100ByRecipientSubAndAudienceAndDeletedAtIsNullOrderByCreatedAtDesc(
            String recipientSub, NotificationAudience audience);

    long countByRecipientSubAndAudienceAndReadAtIsNullAndDeletedAtIsNull(
            String recipientSub, NotificationAudience audience);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipientSub = :sub "
            + "and n.audience = :audience and n.readAt is null")
    int markAllRead(@Param("sub") String recipientSub, @Param("audience") NotificationAudience audience,
                    @Param("now") Instant now);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipientSub = :sub "
            + "and n.id in :ids and n.readAt is null")
    int markRead(@Param("sub") String recipientSub, @Param("ids") Collection<UUID> ids,
                 @Param("now") Instant now);
}
