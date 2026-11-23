package com.raiji.rides.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raiji.rides.config.StripeProperties;
import com.raiji.rides.service.stripe.StripePaymentGateway;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.net.Webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook do Stripe (rota pública, sem login: quem garante a origem é a assinatura).
 * Cadastre no Stripe a URL {@code <Kong>/api/public/stripe/webhook} com os eventos
 * {@code payment_intent.*}; em desenvolvimento: {@code stripe listen --forward-to ...}.
 *
 * <p>Não confia no corpo do evento: busca o PaymentIntent de novo no Stripe e avisa
 * quem tiver esse pagamento ({@link PaymentIntentChanged}). Sem o webhook o sistema
 * continua correto — o app consulta a situação — mas um Pix pago com o app fechado
 * só seria visto na próxima consulta.
 */
@RestController
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeProperties properties;
    private final StripePaymentGateway gateway;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    public StripeWebhookController(StripeProperties properties, StripePaymentGateway gateway,
                                   ApplicationEventPublisher events, ObjectMapper json) {
        this.properties = properties;
        this.gateway = gateway;
        this.events = events;
        this.json = json;
    }

    @PostMapping("/api/public/stripe/webhook")
    public ResponseEntity<Void> handle(@RequestBody String payload,
                                       @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        String secret = properties.webhookSecret();
        if (secret == null || secret.isBlank() || !properties.configured()) {
            return ResponseEntity.status(503).build();
        }
        try {
            Webhook.constructEvent(payload, signature, secret);
        } catch (SignatureVerificationException | RuntimeException e) {
            log.warn("Webhook do Stripe recusado (assinatura inválida): {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        try {
            JsonNode event = json.readTree(payload);
            String type = event.path("type").asText("");
            String id = event.path("data").path("object").path("id").asText("");
            if (type.startsWith("payment_intent.") && !id.isBlank()) {
                events.publishEvent(new PaymentIntentChanged(gateway.retrieve(id)));
            }
        } catch (StripeException e) {
            // 5xx: o Stripe tenta de novo mais tarde
            log.warn("Webhook do Stripe: falha ao consultar o pagamento: {}", e.getMessage());
            return ResponseEntity.status(502).build();
        } catch (Exception e) {
            log.warn("Webhook do Stripe ignorado: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
