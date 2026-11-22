package com.raiji.rides.rewards;

import java.time.Instant;
import java.util.List;

/** O que a tela de recompensas mostra (mesmo formato do app: types/rewards.ts) */
public record RewardsState(List<CouponView> coupons, Challenge challenge, boolean mockAds) {

    /** status: available, used, expired */
    public record CouponView(String id, String code, String title, String description, long discountCents,
                             String status, Instant expiresAt) {

        static CouponView of(CouponRepository.Coupon c, Instant now) {
            String status = c.usedAt() != null ? "used" : c.expiresAt().isBefore(now) ? "expired" : "available";
            return new CouponView(c.id().toString(), c.code(), c.title(), c.description(), c.discountCents(),
                    status, c.expiresAt());
        }
    }

    public record Challenge(String id, String title, long rewardCents, int goal, int progress, boolean completed) {
    }
}
