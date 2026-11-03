package com.raiji.rides.service.stripe;

import java.util.HashMap;
import java.util.Map;

import com.raiji.rides.config.StripeProperties;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;

import org.springframework.stereotype.Component;

/**
 * S2-05 / RN04 — implementação de produção do {@link StripeRefundGateway}
 * usando o SDK oficial {@code com.stripe:stripe-java}.
 *
 * <p>A chave é passada por {@link RequestOptions} (não alteramos o estado
 * global {@code Stripe.apiKey}), o que mantém a chamada isolada e thread-safe.
 * Em sandbox basta usar uma chave {@code sk_test_*} via {@code STRIPE_API_KEY}.
 */
@Component
public class StripeRefundGatewayImpl implements StripeRefundGateway {

    private final StripeProperties properties;

    public StripeRefundGatewayImpl(StripeProperties properties) {
        this.properties = properties;
    }

    @Override
    public String createRefund(String paymentIntentId, long amountCents)
            throws StripeException {

        Map<String, Object> params = new HashMap<>();
        params.put("payment_intent", paymentIntentId);
        if (amountCents > 0) {
            params.put("amount", amountCents);
        }

        RequestOptions options = RequestOptions.builder()
                .setApiKey(properties.apiKey())
                .build();

        Refund refund = Refund.create(params, options);
        return refund.getId();
    }
}
