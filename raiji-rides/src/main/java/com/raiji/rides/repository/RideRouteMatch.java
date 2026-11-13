package com.raiji.rides.repository;

import java.util.UUID;

/** Resultado da busca pelo caminho: onde o embarque/desembarque cai na rota (0 = origem, 1 = destino). */
public interface RideRouteMatch {
    UUID getId();
    Double getPickupFraction();
    Double getDropoffFraction();
    Double getPickupDistance();
    Double getDropoffDistance();
}
