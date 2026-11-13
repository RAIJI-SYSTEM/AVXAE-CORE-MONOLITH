package com.raiji.rides.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Regras de negócio liga/desliga das caronas ({@code raiji.rules}).
 *
 * @param intermunicipalRequiresBusiness quando {@code true}, só motorista com
 *        conta PJ (CNPJ/MEI) publica carona entre cidades diferentes.
 *        Desligada por enquanto ({@code false}): qualquer motorista aprovado publica.
 */
@ConfigurationProperties(prefix = "raiji.rules")
public record RideRulesProperties(boolean intermunicipalRequiresBusiness) {}
