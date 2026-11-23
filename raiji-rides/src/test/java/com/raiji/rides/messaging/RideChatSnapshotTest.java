package com.raiji.rides.messaging;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RideChatSnapshotTest {

    private static RidePassenger booking(String sub, String name, RidePassengerStatus status) {
        RidePassenger p = new RidePassenger();
        p.setPassengerSub(sub);
        p.setPassengerName(name);
        switch (status) {
            case CONFIRMED -> p.confirm();
            case COMPLETED -> p.complete();
            case CANCELLED -> p.cancel("teste");
            default -> { }
        }
        return p;
    }

    @Test
    @DisplayName("Grupo da carona: motorista + passageiros aceitos ou que já viajaram")
    void membros() {
        Ride ride = mock(Ride.class);
        UUID id = UUID.randomUUID();
        when(ride.getId()).thenReturn(id);
        when(ride.getDriverSub()).thenReturn("motorista");
        when(ride.getDriverName()).thenReturn("Ana");
        when(ride.getOriginCity()).thenReturn("Campinas");
        when(ride.getDestinationCity()).thenReturn("Santos");
        when(ride.getDepartureDate()).thenReturn(LocalDateTime.of(2026, 12, 5, 8, 0));
        when(ride.getStatus()).thenReturn(RideStatus.PLANNED);

        var snapshot = RideChatSnapshot.of(ride, List.of(
                booking("p1", "Bia", RidePassengerStatus.CONFIRMED),
                booking("p2", "Caio", RidePassengerStatus.PENDING),
                booking("p3", "Davi", RidePassengerStatus.CANCELLED),
                booking("p4", "Eva", RidePassengerStatus.COMPLETED)), Instant.EPOCH);

        assertThat(snapshot.type()).isEqualTo("ride.synced");
        assertThat(snapshot.rideId()).isEqualTo(id.toString());
        assertThat(snapshot.name()).isEqualTo("Campinas → Santos · 05/12");
        assertThat(snapshot.passengers()).extracting(RideChatSnapshot.Member::userId).containsExactly("p1", "p4");
    }
}
