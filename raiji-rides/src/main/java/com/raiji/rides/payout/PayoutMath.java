package com.raiji.rides.payout;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Parte do motorista: valor pago menos a comissão (mesmo arredondamento de PriceBreakdown). */
public final class PayoutMath {

    private PayoutMath() {
    }

    public static long feeCents(long paidCents, BigDecimal feePercent) {
        if (feePercent == null || feePercent.signum() <= 0) return 0;
        return BigDecimal.valueOf(paidCents).multiply(feePercent)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static long driverShareCents(long paidCents, BigDecimal feePercent) {
        return paidCents - feeCents(paidCents, feePercent);
    }
}
