package com.raiji.rides.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Comissão da plataforma sobre caronas pagas ({@code raiji.pricing}).
 *
 * <p>O passageiro paga exatamente o preço anunciado pelo motorista; a comissão
 * é descontada do valor que o motorista recebe. O percentual vigente é
 * GRAVADO na carona quando ela é publicada: mudar a configuração não altera
 * caronas já publicadas.
 *
 * @param platformFeePercent percentual da comissão (ex.: 1.5 = 1,5%); 0 desliga
 */
@ConfigurationProperties(prefix = "raiji.pricing")
public record RidePricingProperties(BigDecimal platformFeePercent) {

    public RidePricingProperties {
        if (platformFeePercent == null) {
            platformFeePercent = BigDecimal.ZERO;
        }
        if (platformFeePercent.signum() < 0 || platformFeePercent.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(
                    "raiji.pricing.platform-fee-percent deve estar entre 0 e 100.");
        }
    }
}
