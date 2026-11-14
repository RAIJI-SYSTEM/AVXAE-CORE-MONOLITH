package com.raiji.rides.dto;

import java.math.BigDecimal;

/**
 * Uma carona encontrada na busca pelo caminho, com o trecho de quem buscou.
 *
 * @param pickupDistanceMeters  quanto o embarque fica longe da rota
 * @param dropoffDistanceMeters quanto o desembarque fica longe da rota (null sem destino)
 * @param routeFraction         parte da rota que a pessoa percorre (0..1)
 * @param fullRoute             o trecho é (praticamente) a viagem inteira
 * @param suggestedPrice        preço sugerido do trecho (null = gratuita)
 * @param vehicle               ex.: "Fiat Argo · Branco"
 * @param suggestedPackagePrice preço sugerido por encomenda no trecho (null = grátis/não leva)
 */
public record RideSearchResult(
        RideResponse ride,
        int pickupDistanceMeters,
        Integer dropoffDistanceMeters,
        double routeFraction,
        boolean fullRoute,
        BigDecimal suggestedPrice,
        String vehicle,
        BigDecimal suggestedPackagePrice
) {
}
