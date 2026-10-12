package com.raiji.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("Testes unitários (§4) — conversão das roles do JWT em authorities")
class KeycloakAuthoritiesConverterTest {

    private final KeycloakAuthoritiesConverter conversor =
            new KeycloakAuthoritiesConverter("raiji-api");

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .claims(c -> c.putAll(claims)).build();
    }

    @Test
    @DisplayName("CT-U06: realm roles ganham o prefixo ROLE_")
    void realmRoles() {
        var authorities = conversor.convert(jwt(
                Map.of("sub", "user1", "realm_access", Map.of("roles", List.of("DRIVER", "admin")))));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_DRIVER", "ROLE_admin");
    }

    @Test
    @DisplayName("CT-U07: client roles do client raiji-api são extraídas (de outros clients, não)")
    void clientRoles() {
        var jwt = jwt(Map.of("sub", "user1", "resource_access", Map.of(
                "raiji-api", Map.of("roles", List.of("API_ROLE")),
                "outro-client", Map.of("roles", List.of("IGNORADA")))));

        assertThat(conversor.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_API_ROLE");
    }

    @Test
    @DisplayName("CT-U08: token sem claims de roles → authorities vazias")
    void semClaimsDeRoles() {
        // O Spring Security 6+ exige que o Jwt tenha pelo menos um claim.
        // Adicionamos "sub" para satisfazer a validação, mas sem as chaves de roles.
        assertThat(conversor.convert(jwt(Map.of("sub", "usuario-teste")))).isEmpty();
    }
}
