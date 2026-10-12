package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testes de API (§5) — autenticação (RN21)")
class AutenticacaoApiIT extends RaijiTestSupport {

    @Test
    @DisplayName("CT-003: POST /token com credenciais válidas → 200 + access_token")
    void ct003_loginValido() {
        given().contentType(ContentType.URLENC)
                .formParam("grant_type", "password")
                .formParam("client_id", CLIENTE_API)
                .formParam("username", USUARIO_TESTE)
                .formParam("password", SENHA_TESTE)
                .when().post(urlToken())
                .then().statusCode(200)
                .body("access_token", notNullValue())
                .body("token_type", equalTo("Bearer"))
                .body("expires_in", notNullValue());
    }

    @Test
    @DisplayName("CT-004: POST /token com senha incorreta → 401 + invalid_grant")
    void ct004_senhaIncorreta() {
        given().contentType(ContentType.URLENC)
                .formParam("grant_type", "password")
                .formParam("client_id", CLIENTE_API)
                .formParam("username", USUARIO_TESTE)
                .formParam("password", "senha-errada")
                .when().post(urlToken())
                .then().statusCode(401)
                .body("error", equalTo("invalid_grant"));
    }

    @Test
    @DisplayName("CT-005: GET /api/me sem token → 401")
    void ct005_semToken() {
        given().when().get(app("/api/me")).then().statusCode(401);
    }

    @Test
    @DisplayName("CT-006 (RN21): token com assinatura adulterada → 401 (validação local via JWKS)")
    void ct006_tokenAdulterado() {
        String token = adulterarAssinatura(obterToken(USUARIO_TESTE, SENHA_TESTE));
        given().auth().oauth2(token)
                .when().get(app("/api/me"))
                .then().statusCode(401);
    }

    @Test
    @DisplayName("CT-007: GET /api/me → sub, e-mail e realmRoles do JWT")
    void ct007_identidade() {
        given().auth().oauth2(obterToken(USUARIO_TESTE, SENHA_TESTE))
                .when().get(app("/api/me"))
                .then().statusCode(200)
                .body("sub", notNullValue())
                .body("email", equalTo("teste@raiji.com"))
                .body("realmRoles", notNullValue());
    }
}
