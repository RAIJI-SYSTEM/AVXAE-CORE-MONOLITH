package com.raiji.rides.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Preço sugerido de um TRECHO da viagem: proporcional à parte da rota percorrida.
 *
 * <p>Ex.: viagem de R$ 100 e o passageiro vai de 25% a 75% da rota => R$ 50,00.
 * Trechos de 90% da rota ou mais pagam o preço cheio (e contam como "viagem inteira").
 * Carona gratuita continua gratuita.
 */
public final class SegmentPricing {

    /** A partir desta fração da rota, o pedido é tratado como a viagem inteira. */
    public static final double FULL_ROUTE_THRESHOLD = 0.9;

    private SegmentPricing() {
    }

    public static double fraction(Double pickupFraction, Double dropoffFraction) {
        double start = pickupFraction == null ? 0 : pickupFraction;
        double end = dropoffFraction == null ? 1 : dropoffFraction;
        return Math.max(0, Math.min(1, end - start));
    }

    public static boolean isFullRoute(double fraction) {
        return fraction >= FULL_ROUTE_THRESHOLD;
    }

    public static BigDecimal price(BigDecimal fullPrice, double fraction) {
        if (fullPrice == null) {
            return null;
        }
        if (isFullRoute(fraction)) {
            return fullPrice.setScale(2, RoundingMode.HALF_UP);
        }
        return fullPrice.multiply(BigDecimal.valueOf(fraction)).setScale(2, RoundingMode.HALF_UP);
    }
}
