package com.raiji.rides.rewards;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recompensas por pessoa: cupom de boas-vindas e a missão "assista anúncios e ganhe um cupom".
 * Os pontos só entram uma vez por anúncio ({@code transactionId} do AdMob).
 */
@Service
public class RewardsService {

    /** Anúncio curto (intersticial premiado) vale 1; o longo (vídeo premiado), 3 */
    public static final Map<String, Integer> AD_POINTS = Map.of("short", 1, "long", 3);

    static final String CHALLENGE_ID = "primeiro-cupom";
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CouponRepository repository;
    private final RewardsProperties properties;
    private final Clock clock;

    public RewardsService(CouponRepository repository, RewardsProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RewardsState state(String userSub) {
        Instant now = Instant.now(clock);
        if (properties.welcomeCents() > 0) {
            repository.insert(newCoupon(userSub, "WELCOME", properties.welcomeCents(),
                    "Boas-vindas à Raiji", "Vale para caronas ou encomendas.", now));
        }
        // Disponíveis primeiro (os que vencem antes no topo), depois usados e vencidos
        List<RewardsState.CouponView> coupons = repository.listOf(userSub).stream()
                .map(c -> RewardsState.CouponView.of(c, now))
                .sorted(Comparator.comparing((RewardsState.CouponView c) -> !c.status().equals("available"))
                        .thenComparing(RewardsState.CouponView::expiresAt))
                .toList();
        CouponRepository.Progress progress = repository.progressOf(userSub);
        return new RewardsState(coupons, challenge(progress), properties.mockAds());
    }

    /**
     * Credita um anúncio assistido. Idempotente pelo {@code transactionId}.
     * Ao completar a missão, cria o cupom.
     */
    @Transactional
    public RewardsState creditAd(String userSub, String kind, String transactionId) {
        Integer points = AD_POINTS.get(kind);
        if (points == null || transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("Anúncio inválido.");
        }
        CouponRepository.Progress progress = repository.progressOf(userSub);
        if (!progress.completed() && repository.recordAdCredit(transactionId, userSub, kind, points)) {
            int total = Math.min(properties.challengeGoal(), progress.points() + points);
            boolean done = total >= properties.challengeGoal();
            repository.saveProgress(userSub, new CouponRepository.Progress(total, done));
            if (done) {
                Instant now = Instant.now(clock);
                CouponRepository.Coupon coupon;
                do {
                    coupon = newCoupon(userSub, "ADS", properties.challengeRewardCents(),
                            "Seu primeiro cupom", "Prêmio da missão de anúncios. Vale para caronas ou encomendas.", now);
                } while (!repository.insert(coupon));
            }
        }
        return state(userSub);
    }

    private RewardsState.Challenge challenge(CouponRepository.Progress progress) {
        long reais = properties.challengeRewardCents() / 100;
        return new RewardsState.Challenge(CHALLENGE_ID, "Ganhe R$ " + reais + " no seu primeiro cupom",
                properties.challengeRewardCents(), properties.challengeGoal(),
                Math.min(progress.points(), properties.challengeGoal()), progress.completed());
    }

    private CouponRepository.Coupon newCoupon(String userSub, String source, long cents, String title,
                                              String description, Instant now) {
        String code;
        do {
            code = randomCode();
        } while (repository.codeExists(code));
        return new CouponRepository.Coupon(UUID.randomUUID(), userSub, code, title, description, cents, source,
                now.plus(Duration.ofDays(properties.couponValidityDays())), null);
    }

    static String randomCode() {
        StringBuilder code = new StringBuilder("RAIJI");
        for (int i = 0; i < 5; i++) code.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        return code.toString();
    }
}
