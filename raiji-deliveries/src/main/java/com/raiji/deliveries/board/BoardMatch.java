package com.raiji.deliveries.board;

import java.util.UUID;

/** Um pedido do mural que interessa ao motorista: a que distância está e qual carona dele passa no caminho. */
public interface BoardMatch {
    UUID getId();
    Double getDistance();
    UUID getRideId();
}
