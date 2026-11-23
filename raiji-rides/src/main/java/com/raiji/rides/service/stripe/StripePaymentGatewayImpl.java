package com.raiji.rides.service.stripe;

import java.util.Map;

import com.raiji.rides.config.StripeProperties;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementação de produção do {@link StripePaymentGateway} com o SDK oficial.
 * Como no estorno, a chave vai por {@link RequestOptions} (sem estado global).
 */
@Component
public class StripePaymentGatewayImpl implements StripePaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGatewayImpl.class);

    private final StripeProperties properties;

    public StripePaymentGatewayImpl(StripeProperties properties) {
        this.properties = properties;
    }

    @Override
    public PaymentIntentInfo create(long amountCents, String description, Map<String, String> metadata,
                                    String idempotencyKey) throws StripeException {
        try {
            return create(amountCents, description, metadata, idempotencyKey, true);
        } catch (InvalidRequestException e) {
            // Conta sem Pix ativado no Stripe: segue só com cartão em vez de impedir o pagamento
            String erro = String.valueOf(e.getMessage()).toLowerCase();
            if (!erro.contains("pix")) {
                throw e;
            }
            log.warn("Pix indisponível na conta Stripe ({}); cobrança só com cartão.", e.getMessage());
            return create(amountCents, description, metadata, idempotencyKey + ":card", false);
        }
    }

    private PaymentIntentInfo create(long amountCents, String description, Map<String, String> metadata,
                                     String idempotencyKey, boolean withPix) throws StripeException {
        PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency("brl")
                .setDescription(description)
                .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD)
                .putAllMetadata(metadata);
        if (withPix) {
            params.addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.PIX);
        }
        RequestOptions options = RequestOptions.builder()
                .setApiKey(properties.apiKey())
                .setIdempotencyKey(idempotencyKey)
                .build();
        return toInfo(PaymentIntent.create(params.build(), options));
    }

    @Override
    public PaymentIntentInfo retrieve(String paymentIntentId) throws StripeException {
        RequestOptions options = RequestOptions.builder().setApiKey(properties.apiKey()).build();
        return toInfo(PaymentIntent.retrieve(paymentIntentId, options));
    }

    private static PaymentIntentInfo toInfo(PaymentIntent pi) {
        return new PaymentIntentInfo(
                pi.getId(),
                pi.getClientSecret(),
                pi.getStatus(),
                pi.getAmount() == null ? 0 : pi.getAmount(),
                pi.getAmountReceived() == null ? 0 : pi.getAmountReceived(),
                pi.getMetadata() == null ? Map.of() : pi.getMetadata());
    }
}
