package com.raiji.rides.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.raiji.common.enums.PaymentStatus;
import com.raiji.rides.config.StripeProperties;
import com.raiji.rides.rewards.CouponService;
import com.raiji.rides.service.stripe.PaymentIntentInfo;
import com.raiji.rides.service.stripe.StripePaymentGateway;
import com.stripe.exception.StripeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pagamentos pelo Stripe, iguais para reservas de carona e encomendas.
 *
 * <p>O backend é a fonte da verdade: ele cria a cobrança com o valor do pedido e só marca
 * como pago depois de conferir o PaymentIntent no próprio Stripe (ao consultar ou pelo
 * webhook). O app nunca informa "paguei" nem o valor.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final StripeProperties properties;
    private final StripePaymentGateway gateway;
    private final Clock clock;
    /** Marca o cupom como usado quando o pagamento é confirmado (opcional nos testes) */
    private CouponService coupons;

    @Autowired(required = false)
    void setCoupons(CouponService coupons) {
        this.coupons = coupons;
    }

    public PaymentService(StripeProperties properties, StripePaymentGateway gateway, Clock clock) {
        this.properties = properties;
        this.gateway = gateway;
        this.clock = clock;
    }

    /**
     * Inicia (ou retoma) o pagamento: devolve o clientSecret para o app abrir o formulário.
     * Se já estiver pago ou com Pix em processamento, devolve a situação sem clientSecret.
     *
     * @param reference identifica o pedido na chave de idempotência (evita cobrança duplicada)
     */
    public PaymentResponse start(Payable p, long amountCents, String description,
                                 Map<String, String> metadata, String reference) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este pedido é gratuito: não há nada a pagar.");
        }
        requireConfigured();
        refresh(p);
        PaymentStatus status = statusOf(p, amountCents);
        if (status == PaymentStatus.PAID || status == PaymentStatus.REFUNDED || status == PaymentStatus.PROCESSING) {
            return view(p, amountCents);
        }

        try {
            String previous = p.getPaymentIntentId();
            if (previous != null) {
                PaymentIntentInfo current = gateway.retrieve(previous);
                if (current.reusable() && current.amount() == amountCents) {
                    return new PaymentResponse(PaymentStatus.UNPAID, amountCents, null, current.id(), current.clientSecret());
                }
            }
            // Mesmo pedido + mesmo valor + mesma tentativa => o Stripe devolve a mesma cobrança
            String key = "raiji:" + reference + ":" + amountCents + ":" + (previous == null ? "1" : previous);
            PaymentIntentInfo created = gateway.create(amountCents, description, metadata, key);
            p.setPaymentIntentId(created.id());
            p.setPaymentStatus(PaymentStatus.UNPAID);
            return new PaymentResponse(PaymentStatus.UNPAID, amountCents, null, created.id(), created.clientSecret());
        } catch (StripeException e) {
            log.error("Falha ao criar cobrança no Stripe ({}): {}", reference, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível iniciar o pagamento. Tente de novo.");
        }
    }

    /**
     * Consulta o Stripe e atualiza a situação. Nunca lança exceção.
     *
     * @return {@code true} se o pedido acabou de ficar pago (para avisar quem recebe)
     */
    public boolean refresh(Payable p) {
        if (p.getPaymentIntentId() == null || isFinal(p) || !properties.configured()) {
            return false;
        }
        try {
            return apply(p, gateway.retrieve(p.getPaymentIntentId()));
        } catch (StripeException e) {
            log.warn("Não foi possível consultar o pagamento {} no Stripe: {}", p.getPaymentIntentId(), e.getMessage());
            return false;
        }
    }

    /**
     * Aplica o estado do PaymentIntent ao pedido (só se for a cobrança dele).
     *
     * @return {@code true} se o pedido acabou de ficar pago
     */
    public boolean apply(Payable p, PaymentIntentInfo intent) {
        if (!intent.id().equals(p.getPaymentIntentId()) || isFinal(p)) {
            return false;
        }
        if (intent.succeeded()) {
            p.setPaymentStatus(PaymentStatus.PAID);
            p.setPaidAt(Instant.now(clock));
            p.setPaidAmountCents(intent.amountReceived() > 0 ? intent.amountReceived() : intent.amount());
            if (coupons != null) coupons.onPaid(p);
            return true;
        }
        p.setPaymentStatus(intent.processing() ? PaymentStatus.PROCESSING : PaymentStatus.UNPAID);
        return false;
    }

    public PaymentResponse view(Payable p, long amountCents) {
        long amount = p.getPaidAmountCents() != null ? p.getPaidAmountCents() : amountCents;
        return new PaymentResponse(statusOf(p, amountCents), amount, p.getPaidAt(), p.getPaymentIntentId(), null);
    }

    /** Situação para mostrar no app (pedido de preço zero não precisa de pagamento). */
    public static PaymentStatus statusOf(Payable p, long amountCents) {
        PaymentStatus stored = p.getPaymentStatus() == null ? PaymentStatus.UNPAID : p.getPaymentStatus();
        return amountCents <= 0 && stored == PaymentStatus.UNPAID ? PaymentStatus.NOT_REQUIRED : stored;
    }

    /** R$ 50,00 -> 5000 centavos (null => 0). */
    public static long toCents(BigDecimal amount) {
        return amount == null ? 0 : amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static boolean isFinal(Payable p) {
        return p.getPaymentStatus() == PaymentStatus.PAID || p.getPaymentStatus() == PaymentStatus.REFUNDED;
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Pagamentos indisponíveis no momento (Stripe não configurado no servidor).");
        }
    }
}
