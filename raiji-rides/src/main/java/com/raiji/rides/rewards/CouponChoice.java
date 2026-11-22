package com.raiji.rides.rewards;

import java.util.UUID;

/**
 * O que fazer com o cupom ao iniciar o pagamento. Corpo do POST: {@code {"coupon": "<id>"}} para
 * usar, {@code {"coupon": "none"}} para tirar; sem corpo, mantém o que já estava.
 */
public record CouponChoice(Mode mode, UUID couponId) {

    public enum Mode { KEEP, REMOVE, USE }

    public static final CouponChoice KEEP = new CouponChoice(Mode.KEEP, null);

    public record Body(String coupon) {
    }

    public static CouponChoice from(Body body) {
        if (body == null || body.coupon() == null) return KEEP;
        if (body.coupon().isBlank() || body.coupon().equalsIgnoreCase("none")) return new CouponChoice(Mode.REMOVE, null);
        try {
            return new CouponChoice(Mode.USE, UUID.fromString(body.coupon().trim()));
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Cupom inválido.");
        }
    }
}
