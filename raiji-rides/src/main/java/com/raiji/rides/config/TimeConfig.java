package com.raiji.rides.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Relógio injetável (testes trocam por um {@code Clock.fixed(...)}).
 *
 * <p>Os horários das caronas são "hora de parede" no Brasil (o app envia
 * {@code 2026-11-20T08:00} querendo dizer 8h em Brasília). Por isso o relógio
 * usa o fuso {@code raiji.time-zone} (padrão America/Sao_Paulo), e não o do
 * servidor: no Docker o fuso é UTC e "agora" ficaria 3h adiantado.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${raiji.time-zone:America/Sao_Paulo}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
