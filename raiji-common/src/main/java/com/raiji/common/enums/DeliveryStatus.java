package com.raiji.common.enums;

/**
 * Encomenda numa carona (mesmo vocabulário do pedido de vaga):
 * PENDING (pedido) -> CONFIRMED (motorista aceitou) -> IN_TRANSIT (coletada com o código)
 * -> DELIVERED (entregue com a palavra-chave). CANCELLED: recusada ou cancelada.
 */
public enum DeliveryStatus {
    PENDING,
    CONFIRMED,
    IN_TRANSIT,
    DELIVERED,
    CANCELLED
}
