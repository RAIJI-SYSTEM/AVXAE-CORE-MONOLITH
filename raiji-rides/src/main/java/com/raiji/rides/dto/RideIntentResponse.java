package com.raiji.rides.dto;

import com.raiji.rides.domain.RideIntent;
import com.raiji.rides.domain.enums.RideIntentMode;
import com.raiji.rides.domain.enums.RideIntentStatus;
import com.raiji.rides.domain.enums.RideIntentType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RideIntentResponse(
        UUID id,
        String userSub,
        RideIntentType type,
        RideIntentMode mode,
        RideIntentStatus status,
        String originCity,
        String originState,
        Double originLatitude,
        Double originLongitude,
        String destinationCity,
        String destinationState,
        Double destinationLatitude,
        Double destinationLongitude,
        OffsetDateTime desiredAt,
        OffsetDateTime expiresAt
) {
    public static RideIntentResponse from(RideIntent intent) {
        return new RideIntentResponse(
                intent.getId(),
                intent.getUserSub(),
                intent.getType(),
                intent.getMode(),
                intent.getStatus(),
                intent.getOriginCity(),
                intent.getOriginState(),
                intent.getOriginLocation() == null ? null
                        : intent.getOriginLocation().getY(),
                intent.getOriginLocation() == null ? null
                        : intent.getOriginLocation().getX(),
                intent.getDestinationCity(),
                intent.getDestinationState(),
                intent.getDestinationLocation() == null ? null
                        : intent.getDestinationLocation().getY(),
                intent.getDestinationLocation() == null ? null
                        : intent.getDestinationLocation().getX(),
                intent.getDesiredAt(),
                intent.getExpiresAt()
        );
    }
}