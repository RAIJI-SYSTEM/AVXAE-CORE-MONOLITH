package com.raiji.rides.payment;

import com.raiji.rides.service.stripe.PaymentIntentInfo;

/** O Stripe avisou (webhook) que um PaymentIntent mudou; quem tiver esse pagamento se atualiza. */
public record PaymentIntentChanged(PaymentIntentInfo intent) {
}
