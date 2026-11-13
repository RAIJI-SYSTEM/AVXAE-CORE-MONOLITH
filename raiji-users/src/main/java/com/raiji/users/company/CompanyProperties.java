package com.raiji.users.company;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code raiji.companies}: onde consultar o CNPJ.
 *
 * @param cnpjBaseUrl base da BrasilAPI (os testes apontam para um servidor falso)
 * @param timeoutSeconds tempo máximo da consulta
 */
@ConfigurationProperties(prefix = "raiji.companies")
public record CompanyProperties(String cnpjBaseUrl, Integer timeoutSeconds) {
    public CompanyProperties {
        if (cnpjBaseUrl == null || cnpjBaseUrl.isBlank()) cnpjBaseUrl = "https://brasilapi.com.br";
        if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = 15;
    }
}
