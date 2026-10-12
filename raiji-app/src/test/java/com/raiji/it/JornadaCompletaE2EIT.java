package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Teste E2E (§6.1) — jornada completa do usuário")
class JornadaCompletaE2EIT extends RaijiTestSupport {

    @Test
    @DisplayName("CT-023: cadastro → login → identidade → 1º acesso → perfil → role no token")
    void jornadaCompleta() {
        String usuario = "e2e-" + System.currentTimeMillis();

        passo(1, "cadastro do usuário (Admin REST, HTTP 201)");
        criarUsuario(usuario);

        passo(2, "login (password grant) → access_token");
        String token = obterToken(usuario, SENHA_NOVOS_USUARIOS);

        passo(3, "GET /api/me → identidade do usuário autenticado");
        given().auth().oauth2(token).when().get(app("/api/me"))
                .then().statusCode(200)
                .body("sub", notNullValue())
                .body("email", equalTo(usuario + "@raiji.com"));

        passo(4, "GET /api/users/me → 1º acesso provisiona o perfil (RN20)");
        given().auth().oauth2(token).when().get(app("/api/users/me"))
                .then().statusCode(200)
                .body("profileConfigured", is(false));

        passo(5, "POST /api/users/me/profile → define PASSENGER (RN01/RF34)");
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body("{\"role\":\"PASSENGER\"}")
                .when().post(app("/api/users/me/profile"))
                .then().statusCode(200)
                .body("profileType", equalTo("PASSENGER"));

        passo(6, "novo login → token contém a role sincronizada (RF34)");
        String novoToken = obterToken(usuario, SENHA_NOVOS_USUARIOS);
        given().auth().oauth2(novoToken).when().get(app("/api/me"))
                .then().statusCode(200)
                .body("realmRoles", hasItem("PASSENGER"));
    }

    private static void passo(int n, String descricao) {
        System.out.printf("  [CT-023] passo %d/6 — %s%n", n, descricao);
    }
}
