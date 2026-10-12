package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testes de API (§5) — autorização (RN22)")
class AutorizacaoAdminApiIT extends RaijiTestSupport {

    @Test
    @DisplayName("CT-020: GET /api/admin/ping sem token → 401")
    void ct020_semToken() {
        given().when().get(app("/api/admin/ping")).then().statusCode(401);
    }

    @Test
    @DisplayName("CT-019 (RN22): sem a role admin → 403; com a role atribuída via Admin REST → 200")
    void ct019_roleAdmin() {
        String usuario = "admin-it-" + System.currentTimeMillis();
        String userId = criarUsuario(usuario);
        String token = obterToken(usuario, SENHA_NOVOS_USUARIOS);

        given().auth().oauth2(token)
                .when().get(app("/api/admin/ping"))
                .then().statusCode(403);

        atribuirRoleRealm(userId, "admin");
        String tokenAdmin = obterToken(usuario, SENHA_NOVOS_USUARIOS);

        given().auth().oauth2(tokenAdmin)
                .when().get(app("/api/admin/ping"))
                .then().statusCode(200)
                .body("message", containsString("admin"));
    }
}
