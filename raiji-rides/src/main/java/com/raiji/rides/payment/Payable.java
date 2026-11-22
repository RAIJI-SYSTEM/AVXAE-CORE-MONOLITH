package com.raiji.rides.payment;

import com.raiji.common.enums.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/** Algo que se paga pelo Stripe: reserva de vaga numa carona ou encomenda. */
public interface Payable {

    String getPaymentIntentId();

    void setPaymentIntentId(String paymentIntentId);

    PaymentStatus getPaymentStatus();

    void setPaymentStatus(PaymentStatus paymentStatus);

    /** Valor efetivamente recebido pelo Stripe (centavos); usado no estorno. */
    Long getPaidAmountCents();

    void setPaidAmountCents(Long paidAmountCents);

    Instant getPaidAt();

    void setPaidAt(Instant paidAt);

    /** Cupom aplicado (null = sem cupom) */
    UUID getCouponId();

    void setCouponId(UUID couponId);

    /** Desconto do cupom em centavos (o valor cobrado já vem com ele descontado) */
    Long getDiscountCents();

    void setDiscountCents(Long discountCents);

    /** Id do pedido (reserva ou encomenda) */
    UUID getId();
}
