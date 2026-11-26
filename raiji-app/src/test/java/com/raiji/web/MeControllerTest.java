package com.raiji.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class MeControllerTest {

    private final MeController controller = new MeController();

    @Test
    void devolveOsDadosDoToken() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-1")
                .claim("email", "ana@example.com")
                .claim("name", "Ana Souza")
                .claim("realm_access", Map.of("roles", List.of("admin", "user")))
                .claim("cnh_number", "12345678901")
                .claim("cnh_category", "B")
                .claim("cnh_validity", "2030-01-01")
                .claim("driver_verified", "true")
                .build();

        Map<String, Object> resposta = controller.me(jwt);

        assertThat(resposta).containsEntry("sub", "kc-1")
                .containsEntry("email", "ana@example.com")
                .containsEntry("name", "Ana Souza")
                .containsEntry("cnhNumber", "12345678901")
                .containsEntry("cnhCategory", "B")
                .containsEntry("cnhValidity", "2030-01-01")
                .containsEntry("driverVerified", "true");
        assertThat(resposta.get("realmRoles")).isEqualTo(List.of("admin", "user"));
    }

    @Test
    void tokenSemRolesEObservacoesDeCnhDevolveValoresVazios() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-2")
                .build();

        Map<String, Object> resposta = controller.me(jwt);

        assertThat(resposta).containsEntry("sub", "kc-2")
                .containsEntry("email", null)
                .containsEntry("name", null)
                .containsEntry("cnhNumber", null);
        assertThat(resposta.get("realmRoles")).isEqualTo(List.of());
    }

    @Test
    void realAccessSemListaDeRolesTambemFicaVazio() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-3")
                .claim("realm_access", Map.of("outra", "coisa"))
                .build();

        assertThat(controller.me(jwt).get("realmRoles")).isEqualTo(List.of());
    }

    @Test
    void pingDoAdmin() {
        assertThat(controller.adminPing()).containsEntry("message", "Voce tem a role admin do realm raiji");
    }
}
