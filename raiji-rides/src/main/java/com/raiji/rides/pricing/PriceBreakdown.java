package com.raiji.rides.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Quanto do preço de uma vaga fica com a plataforma e quanto vai ao motorista.
 *
 * <p>O passageiro paga {@code price}. A comissão é {@code price × percent / 100},
 * arredondada ao centavo (meio para cima), e o motorista recebe o restante.
 * Carona gratuita ({@code price == null}) não tem comissão: os três valores ficam nulos.
 */
public record PriceBreakdown(
        BigDecimal price,
        BigDecimal platformFeePercent,
        BigDecimal platformFee,
        BigDecimal driverReceives
) {

    public static PriceBreakdown of(BigDecimal price, BigDecimal platformFeePercent) {
        BigDecimal percent = platformFeePercent == null ? BigDecimal.ZERO : platformFeePercent;
        if (price == null) {
            return new PriceBreakdown(null, percent, null, null);
        }
        BigDecimal normalizedPrice = price.setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = normalizedPrice.multiply(percent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new PriceBreakdown(normalizedPrice, percent, fee, normalizedPrice.subtract(fee));
    }
}
