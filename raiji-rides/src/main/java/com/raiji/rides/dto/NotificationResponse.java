package com.raiji.rides.dto;

import java.time.Instant;
import java.util.UUID;

import com.raiji.rides.domain.Notification;
import com.raiji.rides.domain.NotificationAudience;

public record NotificationResponse(
        UUID id,
        NotificationAudience audience,
        String kind,
        UUID rideId,
        UUID referenceId,
        String message,
        boolean read,
        Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getAudience(), n.getKind(), n.getRideId(),
                n.getReferenceId(), n.getMessage(), n.getReadAt() != null, n.getCreatedAt());
    }
}
