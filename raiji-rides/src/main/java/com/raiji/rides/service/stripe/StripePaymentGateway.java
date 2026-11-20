package com.raiji.rides.service.stripe;

import com.stripe.exception.StripeException;

import java.util.Map;

/** Cobranças no Stripe (separado para os testes não chamarem a API real). */
public interface StripePaymentGateway {

    /** Cria a cobrança em reais, aceitando cartão e Pix. */
    PaymentIntentInfo create(long amountCents, String description, Map<String, String> metadata,
                             String idempotencyKey) throws StripeException;

    PaymentIntentInfo retrieve(String paymentIntentId) throws StripeException;
}
