package com.raiji.rides.messaging;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;

/**
 * Evento {@code ride.synced}: a foto atual da carona para o grupo do chat. O chat deixa os
 * membros iguais a esta lista (motorista + passageiros aceitos), então reenviar é inofensivo
 * e um evento perdido se corrige no próximo.
 */
public record RideChatSnapshot(
        String type,
        String rideId,
        String driverId,
        String driverName,
        String name,
        String origin,
        String destination,
        String status,
        Instant occurredAt,
        List<Member> passengers
) {

    public record Member(String userId, String name) {
    }

    /** Passageiros que fazem parte do grupo: aceitos (e os que já viajaram) */
    static final List<RidePassengerStatus> MEMBER_STATUSES =
            List.of(RidePassengerStatus.CONFIRMED, RidePassengerStatus.COMPLETED);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    static RideChatSnapshot of(Ride ride, List<RidePassenger> bookings, Instant now) {
        List<Member> members = bookings.stream()
                .filter(p -> p.getDeletedAt() == null && MEMBER_STATUSES.contains(p.getStatus()))
                .map(p -> new Member(p.getPassengerSub(), p.getPassengerName()))
                .distinct()
                .toList();
        String name = ride.getOriginCity() + " → " + ride.getDestinationCity()
                + (ride.getDepartureDate() != null ? " · " + ride.getDepartureDate().format(DAY) : "");
        return new RideChatSnapshot(MessagingConfig.RIDE_SYNCED, ride.getId().toString(), ride.getDriverSub(),
                ride.getDriverName(), name.length() > 100 ? name.substring(0, 100) : name,
                ride.getOriginCity(), ride.getDestinationCity(), ride.getStatus().name(), now, members);
    }
}
