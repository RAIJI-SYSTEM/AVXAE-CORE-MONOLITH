package com.raiji.common.enums;

/**
 * Situação do pagamento de uma reserva de carona ou de uma encomenda.
 * <pre>
 * UNPAID -> (Pix aguardando) PROCESSING -> PAID -> (cancelou depois de pagar) REFUNDED
 * </pre>
 * NOT_REQUIRED só aparece nas respostas: pedido gratuito (preço zero), nada a pagar.
 */
public enum PaymentStatus {
    NOT_REQUIRED,
    UNPAID,
    PROCESSING,
    PAID,
    REFUNDED
}
