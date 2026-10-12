package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Testes de API (§5) — perfil do usuário (RN01, RN20, RF34)")
class PerfilUsuarioApiIT extends RaijiTestSupport {

    private static final String USUARIO = "perfil-it-" + System.currentTimeMillis();
    private String token;

    @Test
    @Order(1)
    @DisplayName("CT-008: cadastro de usuário via Admin REST → 201 + id")
    void ct008_cadastro() {
        criarUsuario(USUARIO);
    }

    @Test
    @Order(2)
    @DisplayName("CT-009: usuário recém-cadastrado autentica → 200 + token")
    void ct009_login() {
        token = obterToken(USUARIO, SENHA_NOVOS_USUARIOS);
        assertThat(token).isNotBlank();
    }

    @Test
    @Order(3)
    @DisplayName("CT-010 (RN20): 1º acesso provisiona o perfil, ainda não configurado")
    void ct010_primeiroAcesso() {
        given().auth().oauth2(token)
                .when().get(app("/api/users/me"))
                .then().statusCode(200)
                .body("id", notNullValue())
                .body("keycloakSub", notNullValue())
                .body("profileType", nullValue())
                .body("profileConfigured", is(false));
    }

    @Test
    @Order(4)
    @DisplayName("CT-011 (RN01/RF34): definir perfil PASSENGER → 200 + configurado")
    void ct011_definirPerfil() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body("{\"role\":\"PASSENGER\"}")
                .when().post(app("/api/users/me/profile"))
                .then().statusCode(200)
                .body("profileType", equalTo("PASSENGER"))
                .body("profileConfigured", is(true));
    }

    @Test
    @Order(5)
    @DisplayName("CT-012 (RF34): novo login → token contém a role PASSENGER sincronizada")
    void ct012_roleSincronizada() {
        token = obterToken(USUARIO, SENHA_NOVOS_USUARIOS);
        given().auth().oauth2(token)
                .when().get(app("/api/me"))
                .then().statusCode(200)
                .body("realmRoles", hasItem("PASSENGER"));
    }

    @Test
    @Order(6)
    @DisplayName("CT-013: alterar o perfil para DRIVER → 200")
    void ct013_trocarPerfil() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body("{\"role\":\"DRIVER\"}")
                .when().post(app("/api/users/me/profile"))
                .then().statusCode(200)
                .body("profileType", equalTo("DRIVER"));
    }

    @Test
    @Order(7)
    @DisplayName("CT-014 (RF34): após a troca, token novo tem DRIVER e NÃO tem PASSENGER")
    void ct014_trocaDeRole() {
        token = obterToken(USUARIO, SENHA_NOVOS_USUARIOS);
        given().auth().oauth2(token)
                .when().get(app("/api/me"))
                .then().statusCode(200)
                .body("realmRoles", hasItem("DRIVER"))
                .body("realmRoles", not(hasItem("PASSENGER")));
    }

    @Test
    @Order(8)
    @DisplayName("CT-015: reenviar o mesmo perfil é idempotente → 200")
    void ct015_idempotencia() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body("{\"role\":\"DRIVER\"}")
                .when().post(app("/api/users/me/profile"))
                .then().statusCode(200)
                .body("profileType", equalTo("DRIVER"));
    }

    @Test
    @Order(9)
    @DisplayName("CT-016: role fora do enum (\"PIRATE\") → 400")
    void ct016_roleInvalida() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body("{\"role\":\"PIRATE\"}")
                .when().post(app("/api/users/me/profile"))
                .then().statusCode(400);
    }

    @Test
    @Order(10)
    @DisplayName("CT-017: role nula (viola @NotNull) → 400")
    void ct017_roleNula() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body("{\"role\":null}")
                .when().post(app("/api/users/me/profile"))
                .then().statusCode(400);
    }

    @Test
    @Order(11)
    @DisplayName("CT-018: requisição sem corpo → 400")
    void ct018_semCorpo() {
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .when().post(app("/api/users/me/profile"))
                .then().statusCode(400);
    }
}
