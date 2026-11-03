package com.raiji.rides.service.stripe;

/**
 * S2-05 / RN04 — resultado de uma tentativa de estorno no Stripe.
 *
 * <p>Nunca propagamos exceção do Stripe para o fluxo de cancelamento: a
 * falha é representada aqui ({@code ok=false} + {@code error}) e registrada
 * na auditoria como {@code ESTORNO_FALHOU}.
 *
 * @param ok       {@code true} se o refund foi criado com sucesso
 * @param refundId ID do refund no Stripe (ex.: {@code re_...}) quando ok
 * @param error    mensagem de erro quando {@code ok=false}
 */
public record StripeRefundResult(
        boolean ok,
        String refundId,
        String error
) {

    public static StripeRefundResult success(String refundId) {
        return new StripeRefundResult(true, refundId, null);
    }

    public static StripeRefundResult failure(String error) {
        return new StripeRefundResult(false, null, error);
    }
}
