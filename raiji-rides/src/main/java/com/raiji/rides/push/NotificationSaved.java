package com.raiji.rides.push;

import java.util.UUID;

import com.raiji.rides.domain.Notification;

/** Uma notificação foi gravada: vira push depois do commit ({@link NotificationPush}). */
public record NotificationSaved(String recipientSub, String audience, String kind, UUID rideId,
                                UUID referenceId, String message) {

    public static NotificationSaved of(Notification n) {
        return new NotificationSaved(n.getRecipientSub(), n.getAudience() == null ? null : n.getAudience().name(),
                n.getKind(), n.getRideId(), n.getReferenceId(), n.getMessage());
    }
}
