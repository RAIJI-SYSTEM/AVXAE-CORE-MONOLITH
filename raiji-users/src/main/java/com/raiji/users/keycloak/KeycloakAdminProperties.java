package com.raiji.users.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raiji.keycloak")
public record KeycloakAdminProperties(
        String baseUrl,
        String realm,
        String adminClientId,
        String adminClientSecret) {
}
