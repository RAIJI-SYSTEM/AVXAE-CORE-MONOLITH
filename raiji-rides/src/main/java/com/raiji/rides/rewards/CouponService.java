package com.raiji.rides.rewards;

import java.time.Clock;
import java.time.Instant;

import com.raiji.common.enums.PaymentStatus;
import com.raiji.rides.payment.Payable;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cupom no pagamento de reserva ou encomenda.
 * <ul>
 *   <li>o valor cobrado é o preço menos o desconto, nunca abaixo de {@link #MIN_CHARGE_CENTS};</li>
 *   <li>o cupom só conta como usado quando o Stripe confirma o pagamento;</li>
 *   <li>estornou: o cupom volta a valer;</li>
 *   <li>o desconto é da plataforma: o repasse do motorista usa o preço cheio.</li>
 * </ul>
 */
@Service
public class CouponService {

    /** Menor cobrança com cupom (o Stripe cobra no mínimo R$ 0,50 no cartão) */
    public static final long MIN_CHARGE_CENTS = 100;

    private final CouponRepository coupons;
    private final Clock clock;

    public CouponService(CouponRepository coupons, Clock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    /**
     * Aplica a escolha de cupom e devolve o valor a cobrar. Com o pagamento já pago ou em
     * processamento, nada muda (vale o desconto que já estava).
     */
    public long amountToCharge(Payable p, String payerSub, CouponChoice choice, long grossCents) {
        boolean locked = p.getPaymentStatus() == PaymentStatus.PAID || p.getPaymentStatus() == PaymentStatus.REFUNDED
                || p.getPaymentStatus() == PaymentStatus.PROCESSING;
        if (!locked) {
            switch (choice.mode()) {
                case REMOVE -> clear(p);
                case USE -> attach(p, payerSub, choice, grossCents);
                case KEEP -> revalidateKept(p, payerSub, grossCents);
            }
        }
        long discount = p.getDiscountCents() == null ? 0 : p.getDiscountCents();
        return grossCents - discount;
    }

    /** Pagamento confirmado pelo Stripe */
    public void onPaid(Payable p) {
        if (p.getCouponId() != null) coupons.markUsed(p.getCouponId(), Instant.now(clock));
    }

    /** Pagamento estornado: devolve o cupom */
    public void onRefunded(Payable p) {
        if (p.getCouponId() != null) coupons.release(p.getCouponId());
    }

    private void attach(Payable p, String payerSub, CouponChoice choice, long grossCents) {
        CouponRepository.Coupon coupon = coupons.find(choice.couponId())
                .filter(c -> c.userSub().equals(payerSub))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cupom não encontrado."));
        String problem = problemWith(coupon, p, grossCents);
        if (problem != null) throw new ResponseStatusException(HttpStatus.CONFLICT, problem);
        p.setCouponId(coupon.id());
        p.setDiscountCents(discountFor(coupon, grossCents));
    }

    /** Retomando o pagamento: se o cupom deixou de valer (venceu, foi usado), sai sozinho */
    private void revalidateKept(Payable p, String payerSub, long grossCents) {
        if (p.getCouponId() == null) return;
        CouponRepository.Coupon coupon = coupons.find(p.getCouponId())
                .filter(c -> c.userSub().equals(payerSub)).orElse(null);
        if (coupon == null || problemWith(coupon, p, grossCents) != null) {
            clear(p);
        } else {
            p.setDiscountCents(discountFor(coupon, grossCents)); // o preço pode ter mudado
        }
    }

    private String problemWith(CouponRepository.Coupon coupon, Payable p, long grossCents) {
        if (coupon.usedAt() != null) return "Este cupom já foi usado.";
        if (coupon.expiresAt().isBefore(Instant.now(clock))) return "Este cupom venceu.";
        if (grossCents <= MIN_CHARGE_CENTS) return "O valor é baixo demais para usar cupom.";
        if (p.getId() != null && coupons.inOpenPaymentOtherThan(coupon.id(), p.getId())) {
            return "Este cupom está em outro pagamento em aberto. Tire-o de lá para usar aqui.";
        }
        return null;
    }

    static long discountFor(CouponRepository.Coupon coupon, long grossCents) {
        return Math.max(0, Math.min(coupon.discountCents(), grossCents - MIN_CHARGE_CENTS));
    }

    private static void clear(Payable p) {
        p.setCouponId(null);
        p.setDiscountCents(null);
    }
}
