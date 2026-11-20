package com.raiji.rides.payment;

import com.raiji.common.enums.PaymentStatus;

import java.time.Instant;

/**
 * Situação do pagamento de uma reserva ou encomenda.
 *
 * @param clientSecret só vem ao iniciar o pagamento (POST), para o app abrir o formulário do Stripe
 */
public record PaymentResponse(
        PaymentStatus status,
        long amountCents,
        Instant paidAt,
        String paymentIntentId,
        String clientSecret
) {
}
