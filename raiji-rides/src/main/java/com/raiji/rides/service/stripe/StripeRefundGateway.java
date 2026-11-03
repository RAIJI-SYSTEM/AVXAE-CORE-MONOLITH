package com.raiji.rides.service.stripe;

/**
 * S2-05 / RN04 — "seam" (ponto de substituição) para a chamada real ao
 * Stripe. A implementação de produção ({@link StripeRefundGatewayImpl}) usa o
 * SDK {@code com.stripe}; nos testes injetamos um fake determinístico, sem
 * rede e sem chave real (o aceite da issue exige {@code sk_test_*} e "não faz
 * chamadas reais de cobrança").
 */
public interface StripeRefundGateway {

    /**
     * Cria um estorno (total ou parcial) sobre um PaymentIntent.
     *
     * @param paymentIntentId identificador do pagamento ({@code pi_...})
     * @param amountCents     valor em centavos; {@code <= 0} => estorno total
     * @return ID do refund criado ({@code re_...})
     * @throws Exception qualquer falha do provedor (tratada pelo {@code StripeService})
     */
    String createRefund(String paymentIntentId, long amountCents) throws Exception;
}
