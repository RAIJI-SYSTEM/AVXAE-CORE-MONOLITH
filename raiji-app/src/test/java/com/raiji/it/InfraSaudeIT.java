package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testes de API (§5) — infraestrutura e pré-condições")
class InfraSaudeIT extends RaijiTestSupport {

    @Test
    @DisplayName("CT-001: GET /actuator/health → 200 + status UP")
    void ct001_healthcheck() {
        given().when().get(app("/actuator/health"))
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    @DisplayName("CT-002: Keycloak acessível — metadados do realm e JWKS (RN21)")
    void ct002_keycloakAcessivel() {
        given().when().get(kc("/realms/" + REALM))
                .then().statusCode(200)
                .body("realm", equalTo(REALM));

        given().when().get(kc("/realms/" + REALM + "/protocol/openid-connect/certs"))
                .then().statusCode(200)
                .body("keys.size()", greaterThan(0));
    }
}
