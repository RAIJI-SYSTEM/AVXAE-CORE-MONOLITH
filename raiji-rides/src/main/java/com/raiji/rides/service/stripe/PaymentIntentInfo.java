package com.raiji.rides.service.stripe;

import java.util.Map;

/**
 * O que o backend precisa de um PaymentIntent do Stripe.
 *
 * @param status         status do Stripe: requires_payment_method, processing, succeeded, canceled...
 * @param amountReceived centavos efetivamente recebidos (0 até o pagamento concluir)
 */
public record PaymentIntentInfo(
        String id,
        String clientSecret,
        String status,
        long amount,
        long amountReceived,
        Map<String, String> metadata
) {
    public boolean succeeded() {
        return "succeeded".equals(status);
    }

    public boolean processing() {
        return "processing".equals(status);
    }

    /** Ainda dá para o cliente pagar com este PaymentIntent (não foi cancelado nem concluído). */
    public boolean reusable() {
        return switch (status) {
            case "requires_payment_method", "requires_confirmation", "requires_action" -> true;
            default -> false;
        };
    }
}
