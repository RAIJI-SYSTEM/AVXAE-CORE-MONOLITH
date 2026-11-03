package com.raiji.rides.service.stripe;

import com.raiji.rides.config.StripeProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * S2-05 / RN04 — fachada de estorno do Stripe.
 *
 * <p>Contrato da issue: {@code refund(paymentIntentId, amountCents)}. Este
 * método <b>nunca propaga exceção</b> — qualquer falha (Stripe indisponível,
 * chave inválida, PaymentIntent inexistente) vira um {@link StripeRefundResult}
 * com {@code ok=false}, para que o cancelamento da reserva seja sempre
 * concluído (critério: "Falha no Stripe não impede cancelamento").
 */
@Service
public class StripeService {

    private static final Logger log =
            LoggerFactory.getLogger(StripeService.class);

    private final StripeProperties properties;
    private final StripeRefundGateway gateway;

    public StripeService(StripeProperties properties, StripeRefundGateway gateway) {
        this.properties = properties;
        this.gateway = gateway;
    }

    /**
     * Estorna {@code amountCents} do pagamento {@code paymentIntentId}.
     *
     * @return resultado com {@code refundId} (sucesso) ou {@code error} (falha)
     */
    public StripeRefundResult refund(String paymentIntentId, long amountCents) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return StripeRefundResult.failure("paymentIntentId ausente");
        }

        if (!properties.configured()) {
            // Sem chave (ex.: ambiente local/CI sem segredo) => não chamamos a
            // API real; a falha é controlada e registrada como ESTORNO_FALHOU.
            return StripeRefundResult.failure(
                    "Stripe não configurado (raiji.stripe.api-key ausente)");
        }

        try {
            String refundId = gateway.createRefund(paymentIntentId, amountCents);
            log.info(
                    "Estorno Stripe criado: refund={} paymentIntent={} amountCents={}",
                    refundId, paymentIntentId, amountCents);
            return StripeRefundResult.success(refundId);
        } catch (Exception e) {
            String erro = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            log.error(
                    "Falha ao estornar no Stripe (paymentIntent={}): {}",
                    paymentIntentId, erro);
            return StripeRefundResult.failure(erro);
        }
    }
}
