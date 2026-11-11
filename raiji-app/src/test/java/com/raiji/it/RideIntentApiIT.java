package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Intenções de carona "na hora" contra a API real e um PostGIS de verdade
 * (os testes unitários usam mock do banco: as consultas geográficas não rodavam).
 */
@DisplayName("Intenções de carona (busca por proximidade, PostGIS real)")
class RideIntentApiIT extends RaijiTestSupport {

    // Praça da Sé (SP) e pontos de referência
    private static final double SE_LAT = -23.5503, SE_LNG = -46.6340;
    private static final double PAULISTA_LAT = -23.5614, PAULISTA_LNG = -46.6559;   // ~2,5 km da Sé
    private static final double CAMPINAS_LAT = -22.9056, CAMPINAS_LNG = -47.0608;   // ~85 km

    private record Conta(String token, String sub) {}

    private Conta conta(String prefixo, boolean motorista) {
        String username = prefixo + "-" + System.nanoTime();
        String userId = criarUsuario(username);
        if (motorista) atribuirRoleRealm(userId, "DRIVER");
        String token = obterToken(username, SENHA_NOVOS_USUARIOS);
        String sub = given().auth().oauth2(token).when().get(app("/api/users/me")).then().extract().path("sub");
        return new Conta(token, sub);
    }

    private Map<String, Object> pedido(String tipo, String modo, double lat, double lng, OffsetDateTime desiredAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", tipo);
        body.put("mode", modo);
        body.put("originCity", "São Paulo");
        body.put("originState", "SP");
        body.put("originLatitude", lat);
        body.put("originLongitude", lng);
        if ("ROUTE".equals(modo)) {
            body.put("destinationCity", "Campinas");
            body.put("destinationState", "SP");
            body.put("destinationLatitude", CAMPINAS_LAT);
            body.put("destinationLongitude", CAMPINAS_LNG);
        }
        if (desiredAt != null) body.put("desiredAt", desiredAt.toString());
        return body;
    }

    private ValidatableResponse criar(Conta conta, Map<String, Object> body) {
        return given().auth().oauth2(conta.token()).contentType(ContentType.JSON).body(body)
                .when().post(app("/api/ride-intents")).then();
    }

    private List<Map<String, Object>> perto(Conta quem, String tipo, String modo, double lat, double lng, double raio) {
        return given().auth().oauth2(quem.token())
                .queryParam("type", tipo).queryParam("mode", modo)
                .queryParam("latitude", lat).queryParam("longitude", lng).queryParam("radiusMeters", raio)
                .when().get(app("/api/ride-intents/nearby"))
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    private static boolean contem(List<Map<String, Object>> lista, String id) {
        return lista.stream().anyMatch(i -> id.equals(i.get("id")));
    }

    @Test
    @DisplayName("passageira pede carona; outra pessoa a 2,5 km acha (local aproximado + distância); a 85 km não")
    void buscaPorProximidade() {
        Conta ana = conta("intent-ana", false);
        Conta bia = conta("intent-bia", false);
        String id = criar(ana, pedido("PASSENGER_REQUEST", "ROUTE", SE_LAT, SE_LNG, null))
                .statusCode(201).extract().path("id");

        List<Map<String, Object>> achados = perto(bia, "PASSENGER_REQUEST", "ROUTE", PAULISTA_LAT, PAULISTA_LNG, 5000);
        assertThat(contem(achados, id)).isTrue();
        Map<String, Object> encontrado = achados.stream().filter(i -> id.equals(i.get("id"))).findFirst().orElseThrow();
        assertThat(((Number) encontrado.get("originLatitude")).doubleValue()).isCloseTo(-23.55, within(1e-4));  // arredondado (~1 km)
        assertThat(((Number) encontrado.get("originLongitude")).doubleValue()).isCloseTo(-46.63, within(1e-4));
        assertThat(((Number) encontrado.get("distanceMeters")).doubleValue()).isBetween(2000.0, 3000.0);

        assertThat(contem(perto(bia, "PASSENGER_REQUEST", "ROUTE", CAMPINAS_LAT, CAMPINAS_LNG, 5000), id)).isFalse();
        assertThat(contem(perto(ana, "PASSENGER_REQUEST", "ROUTE", SE_LAT, SE_LNG, 5000), id))
                .as("a própria intenção não aparece na busca").isFalse();

        // Nas PRÓPRIAS intenções o local é exato
        List<Map<String, Object>> minhas = given().auth().oauth2(ana.token()).when().get(app("/api/ride-intents/me"))
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Object> minha = minhas.stream().filter(i -> id.equals(i.get("id"))).findFirst().orElseThrow();
        assertThat(((Number) minha.get("originLatitude")).doubleValue()).isCloseTo(SE_LAT, within(1e-4));
    }

    @Test
    @DisplayName("correção: intenção cujo horário desejado já passou continua na busca enquanto não expira")
    void horarioPassadoAindaAtivo() throws InterruptedException {
        Conta ana = conta("intent-horario", false);
        Conta bia = conta("intent-busca", false);
        String id = criar(ana, pedido("PASSENGER_REQUEST", "ROUTE", SE_LAT, SE_LNG, OffsetDateTime.now().plusSeconds(2)))
                .statusCode(201).extract().path("id");

        Thread.sleep(3000); // o horário desejado passou; a intenção ainda vale por ~60 min

        assertThat(contem(perto(bia, "PASSENGER_REQUEST", "ROUTE", SE_LAT, SE_LNG, 5000), id)).isTrue();
    }

    @Test
    @DisplayName("correção: 'estou disponível como motorista' exige DRIVER (CNH verificada) — sem ela, 403")
    void ofertaDeMotoristaExigeDriver() {
        criar(conta("intent-sem-cnh", false), pedido("DRIVER_OFFER", "FREE", SE_LAT, SE_LNG, null))
                .statusCode(403).body("message", containsString("CNH"));

        Conta motorista = conta("intent-motorista", true);
        String id = criar(motorista, pedido("DRIVER_OFFER", "FREE", SE_LAT, SE_LNG, null))
                .statusCode(201).extract().path("id");
        assertThat(contem(perto(conta("intent-passageira", false), "DRIVER_OFFER", "FREE", PAULISTA_LAT, PAULISTA_LNG, 5000), id))
                .isTrue();
    }

    @Test
    @DisplayName("correção: dado inválido responde 400 (antes 500), no formato JSON com message")
    void dadosInvalidos() {
        Conta ana = conta("intent-invalida", false);

        criar(ana, pedido("PASSENGER_REQUEST", "ROUTE", 999, SE_LNG, null))
                .statusCode(400).body("status", equalTo(400)).body("message", containsString("Latitude"));
        criar(ana, pedido("PASSENGER_REQUEST", "FREE", SE_LAT, SE_LNG, null))
                .statusCode(400).body("message", containsString("FREE"));
        given().auth().oauth2(ana.token())
                .queryParam("type", "PASSENGER_REQUEST").queryParam("mode", "ROUTE")
                .queryParam("latitude", SE_LAT).queryParam("longitude", SE_LNG).queryParam("radiusMeters", 500_000)
                .when().get(app("/api/ride-intents/nearby"))
                .then().statusCode(400).body("message", containsString("100 km"));
    }

    @Test
    @DisplayName("cancelar: de outra pessoa 404 (não revela); a própria 204; de novo 409; some da busca")
    void cancelamento() {
        Conta ana = conta("intent-cancela", false);
        Conta bia = conta("intent-outra", false);
        String id = criar(ana, pedido("PASSENGER_REQUEST", "ROUTE", SE_LAT, SE_LNG, null)).statusCode(201).extract().path("id");

        given().auth().oauth2(bia.token()).when().delete(app("/api/ride-intents/" + id)).then().statusCode(404);
        given().auth().oauth2(ana.token()).when().delete(app("/api/ride-intents/" + id)).then().statusCode(204);
        given().auth().oauth2(ana.token()).when().delete(app("/api/ride-intents/" + id))
                .then().statusCode(409).body("message", containsString("ativas"));

        assertThat(contem(perto(bia, "PASSENGER_REQUEST", "ROUTE", SE_LAT, SE_LNG, 5000), id)).isFalse();
    }
}
