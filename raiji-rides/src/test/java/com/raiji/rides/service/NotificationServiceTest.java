package com.raiji.rides.service;

import java.time.Instant;
import java.util.UUID;

import com.raiji.rides.domain.Notification;
import com.raiji.rides.domain.NotificationAudience;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.event.RideCancelledEvent;
import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.event.RideRequestCreatedEvent;
import com.raiji.rides.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A mesma conta pode ser passageira e motorista: cada notificação diz em
 * qual modo do app ela aparece.
 */
class NotificationServiceTest {

    private static final UUID RIDE = UUID.randomUUID();
    private static final String DRIVER = "driver-sub";
    private static final String PASSENGER = "passenger-sub";

    private NotificationRepository repository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        service = new NotificationService(repository);
    }

    private Notification saved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void novaSolicitacaoVaiParaOModoMotorista() {
        service.handleRideRequestCreated(new RideRequestCreatedEvent(
                RIDE, DRIVER, "Carlos", PASSENGER, "Ana", Instant.now()));

        Notification n = saved();
        assertEquals(DRIVER, n.getRecipientSub());
        assertEquals(NotificationAudience.DRIVER, n.getAudience());
    }

    @Test
    void decisaoVaiParaOModoPassageiro() {
        service.handleRideDecision(new RideDecisionEvent(
                RIDE, UUID.randomUUID(), DRIVER, "Carlos", PASSENGER, "Ana",
                RideDecision.ACCEPT, null, Instant.now()));

        Notification n = saved();
        assertEquals(PASSENGER, n.getRecipientSub());
        assertEquals(NotificationAudience.PASSENGER, n.getAudience());
    }

    @Test
    void cancelamentoVaiParaOModoMotorista() {
        service.handleRideCancelled(new RideCancelledEvent(
                RIDE, UUID.randomUUID(), DRIVER, "Carlos", PASSENGER, "Ana",
                "imprevisto", Instant.now(), true));

        Notification n = saved();
        assertEquals(DRIVER, n.getRecipientSub());
        assertEquals(NotificationAudience.DRIVER, n.getAudience());
    }
}
