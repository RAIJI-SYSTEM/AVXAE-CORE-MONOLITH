package com.raiji.rides.payment;

import com.raiji.common.enums.PaymentStatus;

import java.time.Instant;

/**
 * Situação do pagamento de uma reserva ou encomenda.
 *
 * @param amountCents   valor cobrado (já com o desconto do cupom)
 * @param clientSecret  só vem ao iniciar o pagamento (POST), para o app abrir o formulário do Stripe
 * @param discountCents desconto do cupom (0 sem cupom)
 * @param couponId      cupom aplicado (null sem cupom)
 */
public record PaymentResponse(
        PaymentStatus status,
        long amountCents,
        Instant paidAt,
        String paymentIntentId,
        String clientSecret,
        long discountCents,
        String couponId
) {

    public PaymentResponse(PaymentStatus status, long amountCents, Instant paidAt, String paymentIntentId,
                           String clientSecret) {
        this(status, amountCents, paidAt, paymentIntentId, clientSecret, 0, null);
    }

    /** Acrescenta o cupom do pedido */
    public PaymentResponse withCoupon(Payable p) {
        long discount = p.getDiscountCents() == null ? 0 : p.getDiscountCents();
        return new PaymentResponse(status, amountCents, paidAt, paymentIntentId, clientSecret, discount,
                p.getCouponId() == null ? null : p.getCouponId().toString());
    }
}
