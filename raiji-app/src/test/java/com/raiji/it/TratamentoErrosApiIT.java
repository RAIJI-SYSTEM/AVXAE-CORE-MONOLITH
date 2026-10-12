package com.raiji.it;

import static io.restassured.RestAssured.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testes de API (§5) — tratamento de erros HTTP")
class TratamentoErrosApiIT extends RaijiTestSupport {

    @Test
    @DisplayName("CT-021: rota inexistente (autenticado) → 404")
    void ct021_naoEncontrado() {
        given().auth().oauth2(obterToken(USUARIO_TESTE, SENHA_TESTE))
                .when().get(app("/api/rota-inexistente"))
                .then().statusCode(404);
    }

    @Test
    @DisplayName("CT-022: método não suportado (PATCH /api/users/me) → 405")
    void ct022_metodoNaoPermitido() {
        given().auth().oauth2(obterToken(USUARIO_TESTE, SENHA_TESTE))
                .when().patch(app("/api/users/me"))
                .then().statusCode(405);
    }
}
