package com.raiji.rides.rewards;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Regras das recompensas ({@code raiji.rewards.*}).
 *
 * @param welcomeCents       cupom de boas-vindas (0 = sem cupom de boas-vindas)
 * @param couponValidityDays validade dos cupons novos
 * @param challengeGoal      pontos da missão de anúncios (anúncio curto = 1, longo = 3)
 * @param challengeRewardCents valor do cupom ao completar a missão
 * @param mockAds            aceita "assisti" vindo do app (testes). Em produção, false: só o
 *                           callback assinado do AdMob (SSV) credita
 */
@ConfigurationProperties(prefix = "raiji.rewards")
public record RewardsProperties(
        Long welcomeCents,
        Integer couponValidityDays,
        Integer challengeGoal,
        Long challengeRewardCents,
        Boolean mockAds
) {
    public RewardsProperties {
        if (welcomeCents == null) welcomeCents = 500L;
        if (couponValidityDays == null) couponValidityDays = 30;
        if (challengeGoal == null) challengeGoal = 3;
        if (challengeRewardCents == null) challengeRewardCents = 1500L;
        if (mockAds == null) mockAds = false;
    }
}
