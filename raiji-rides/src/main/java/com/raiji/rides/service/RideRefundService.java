package com.raiji.rides.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;
import com.raiji.rides.service.stripe.StripeRefundResult;
import com.raiji.rides.service.stripe.StripeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * S2-05 / RN04 — orquestra o estorno automático no cancelamento de caronas
 * pagas e o respectivo registro de auditoria.
 *
 * <p>Regras (critérios de aceite da issue #12):
 * <ul>
 *   <li>carona <b>gratuita</b> ({@code pricePerPassenger == null}) =&gt; NÃO
 *       chama o Stripe;</li>
 *   <li>reserva sem {@code paymentIntentId} =&gt; nada a estornar;</li>
 *   <li>estorno ok =&gt; auditoria {@code ESTORNO_SUCESSO} com o id do refund;</li>
 *   <li>falha no Stripe =&gt; auditoria {@code ESTORNO_FALHOU}; o cancelamento
 *       segue (este método nunca lança exceção).</li>
 * </ul>
 */
@Service
public class RideRefundService {

    private static final Logger log =
            LoggerFactory.getLogger(RideRefundService.class);

    /** Tipo de entidade gravado na auditoria. */
    public static final String ENTITY_TYPE = "RIDE_PASSENGER";
    /** Ação de auditoria quando o estorno é criado com sucesso. */
    public static final String ACTION_SUCESSO = "ESTORNO_SUCESSO";
    /** Ação de auditoria quando o estorno falha (não impede o cancelamento). */
    public static final String ACTION_FALHOU = "ESTORNO_FALHOU";

    private final StripeService stripeService;
    private final AuditLogService auditLogService;

    public RideRefundService(StripeService stripeService,
                             AuditLogService auditLogService) {
        this.stripeService = stripeService;
        this.auditLogService = auditLogService;
    }

    /**
     * Estorna a reserva cancelada quando aplicável. Nunca lança exceção.
     *
     * @return {@code true} se um estorno foi de fato tentado (pago + paymentIntent)
     */
    public boolean refundIfApplicable(Ride ride, RidePassenger passenger, String actorSub) {
        if (ride.getPricePerPassenger() == null) {
            // Carona gratuita => não chama o Stripe (critério de aceite).
            return false;
        }

        String paymentIntentId = passenger.getPaymentIntentId();
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            log.debug(
                    "Reserva {} sem paymentIntentId — nada a estornar.",
                    passenger.getId());
            return false;
        }

        long amountCents = toCents(ride.getPricePerPassenger());
        StripeRefundResult result = stripeService.refund(paymentIntentId, amountCents);

        if (result.ok()) {
            auditLogService.record(
                    ACTION_SUCESSO,
                    ENTITY_TYPE,
                    passenger.getId(),
                    actorSub,
                    "refundId=" + result.refundId()
                            + "; paymentIntentId=" + paymentIntentId
                            + "; amountCents=" + amountCents
                            + "; rideId=" + ride.getId());
        } else {
            auditLogService.record(
                    ACTION_FALHOU,
                    ENTITY_TYPE,
                    passenger.getId(),
                    actorSub,
                    "error=" + result.error()
                            + "; paymentIntentId=" + paymentIntentId
                            + "; amountCents=" + amountCents
                            + "; rideId=" + ride.getId());
        }

        return true;
    }

    /** Converte um valor monetário (ex.: 50.00) em centavos (5000). */
    static long toCents(BigDecimal amount) {
        return amount.movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
