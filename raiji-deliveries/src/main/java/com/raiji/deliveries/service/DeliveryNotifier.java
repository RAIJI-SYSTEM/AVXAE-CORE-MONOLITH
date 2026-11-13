package com.raiji.deliveries.service;

import java.util.Objects;
import java.util.UUID;

import com.raiji.common.notification.NotificationRequested;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.users.company.CompanyMembership;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Avisos das encomendas e dos fretes (remetente, membros da empresa, motorista). */
@Component
public class DeliveryNotifier {

    private final CompanyMembership membership;
    private final ApplicationEventPublisher events;

    public DeliveryNotifier(CompanyMembership membership, ApplicationEventPublisher events) {
        this.membership = membership;
        this.events = events;
    }

    /** Avisa quem enviou e, se for de empresa, todos os membros (na caixa "empresa"). */
    public void sender(Delivery d, String kind, String message) {
        if (d.getCompanyId() != null) {
            company(d.getCompanyId(), kind, d.getRideId(), d.getId(), message);
        }
        if (d.getCompanyId() == null || !membership.isActiveMember(d.getCompanyId(), d.getSenderSub())) {
            send(d.getSenderSub(), "PASSENGER", kind, d.getRideId(), d.getId(), message);
        }
    }

    public void company(UUID companyId, String kind, UUID rideId, UUID refId, String message) {
        membership.activeMemberSubs(companyId).stream().filter(Objects::nonNull).distinct()
                .forEach(sub -> send(sub, "COMPANY", kind, rideId, refId, message));
    }

    public void driver(String driverSub, String kind, UUID rideId, UUID refId, String message) {
        send(driverSub, "DRIVER", kind, rideId, refId, message);
    }

    private void send(String sub, String audience, String kind, UUID rideId, UUID refId, String message) {
        events.publishEvent(new NotificationRequested(sub, audience, kind, rideId, refId, message));
    }
}
