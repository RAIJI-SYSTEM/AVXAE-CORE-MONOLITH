package com.raiji.rides.payout;

import java.time.Instant;
import java.util.UUID;

/** Um repasse já feito (Pix pela plataforma) */
public record DriverPayout(
        UUID id,
        String driverSub,
        long amountCents,
        int itemCount,
        String pixKey,
        String note,
        Instant paidAt) {
}
