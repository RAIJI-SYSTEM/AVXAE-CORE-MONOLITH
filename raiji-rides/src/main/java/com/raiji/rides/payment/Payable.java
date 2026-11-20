package com.raiji.rides.payment;

import com.raiji.common.enums.PaymentStatus;

import java.time.Instant;

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
}
