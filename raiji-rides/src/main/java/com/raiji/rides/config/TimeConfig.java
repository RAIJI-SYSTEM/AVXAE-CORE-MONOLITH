package com.raiji.rides.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * S2-06 / RF10 + RN05 — fornece um {@link Clock} injetável.
 *
 * <p>Em produção usa o relógio do sistema; nos testes o bean pode ser
 * substituído por um {@code Clock.fixed(...)} (ou mutável) para validar o
 * limite exato {@code departureDate == now()} de forma determinística
 * (observação da issue #13: "usar Clock injetável para testes").
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
