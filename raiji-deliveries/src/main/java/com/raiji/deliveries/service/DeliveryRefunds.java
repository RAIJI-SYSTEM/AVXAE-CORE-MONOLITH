package com.raiji.deliveries.service;

import com.raiji.deliveries.domain.Delivery;
import com.raiji.rides.payment.PaymentService;
import com.raiji.rides.service.RideRefundService;

import org.springframework.stereotype.Component;

/** Estorno de encomenda cancelada depois de paga (mesma regra e auditoria das reservas). */
@Component
public class DeliveryRefunds {

    /** Tipo de entidade gravado na auditoria dos estornos. */
    public static final String ENTITY_TYPE = "DELIVERY";

    private final RideRefundService refunds;

    public DeliveryRefunds(RideRefundService refunds) {
        this.refunds = refunds;
    }

    /** Estorna se estava paga (e marca REFUNDED). Nunca lança exceção. */
    public void refundIfPaid(Delivery d, String actorSub) {
        refunds.refundIfPaid(d, ENTITY_TYPE, d.getId(), actorSub, PaymentService.toCents(d.getPrice()),
                "rideId=" + d.getRideId());
    }
}
