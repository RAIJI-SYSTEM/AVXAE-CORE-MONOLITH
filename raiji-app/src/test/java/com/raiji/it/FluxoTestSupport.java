package com.raiji.it;

import static io.restassured.RestAssured.given;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

/** Apoio dos testes de fluxo (caronas com mapa, encomendas, empresas, frete). */
abstract class FluxoTestSupport extends RaijiTestSupport {

    static final ZoneId BRASIL = ZoneId.of("America/Sao_Paulo");
    static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // Praça da Sé (SP) -> Jundiaí -> Campinas
    static final double[] SE = {-23.5503, -46.6340};
    static final double[] JUNDIAI = {-23.1857, -46.8978};
    static final double[] CAMPINAS = {-22.9056, -47.0608};
    static final double[] RIO = {-22.9068, -43.1729};

    record Conta(String token, String sub, String username) {}

    record Motorista(String token, String sub, String vehicleId) {}

    Conta conta(String prefixo) {
        String username = prefixo + "-" + System.nanoTime();
        String userId = criarUsuario(username);
        String token = obterToken(username, SENHA_NOVOS_USUARIOS);
        return new Conta(token, userId, username);
    }

    Motorista motorista(String prefixo, int vagas, Integer porteMalasLitros) {
        String username = prefixo + "-" + System.nanoTime();
        String userId = criarUsuario(username);
        atribuirRoleRealm(userId, "DRIVER");
        String token = obterToken(username, SENHA_NOVOS_USUARIOS);
        Map<String, Object> v = new HashMap<>();
        v.put("plate", "F" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)));
        v.put("type", "CARRO");
        v.put("model", "Fiat Argo");
        v.put("color", "Branco");
        v.put("year", 2023);
        v.put("passengerSeats", vagas);
        if (porteMalasLitros != null) v.put("cargoVolumeLiters", porteMalasLitros);
        String vehicleId = given().auth().oauth2(token).contentType(ContentType.JSON).body(v)
                .when().post(app("/api/vehicles")).then().statusCode(201).extract().path("id");
        return new Motorista(token, userId, vehicleId);
    }

    static LocalDateTime agora() {
        return LocalDateTime.now(BRASIL);
    }

    static Map<String, Object> lugar(double[] p, String endereco) {
        Map<String, Object> m = new HashMap<>();
        m.put("latitude", p[0]);
        m.put("longitude", p[1]);
        m.put("address", endereco);
        return m;
    }

    static List<Map<String, Object>> rotaSeJundiaiCampinas() {
        return List.of(
                Map.of("latitude", SE[0], "longitude", SE[1]),
                Map.of("latitude", JUNDIAI[0], "longitude", JUNDIAI[1]),
                Map.of("latitude", CAMPINAS[0], "longitude", CAMPINAS[1]));
    }

    /** Carona Sé -> Campinas (passando por Jundiaí). */
    Map<String, Object> carona(Motorista m, String tipo, int vagas, Double preco, LocalDateTime saida) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", m.vehicleId());
        body.put("type", tipo);
        body.put("originCity", "São Paulo");
        body.put("originState", "SP");
        body.put("destinationCity", "Campinas");
        body.put("destinationState", "SP");
        body.put("departureDate", saida.format(ISO));
        if (preco != null) body.put("pricePerPassenger", preco);
        body.put("totalSeats", vagas);
        body.put("origin", lugar(SE, "Praça da Sé"));
        body.put("destination", lugar(CAMPINAS, "Rodoviária de Campinas"));
        body.put("route", rotaSeJundiaiCampinas());
        body.put("meetingPoint", "Em frente à catedral");
        return body;
    }

    ValidatableResponse publicar(Motorista m, Map<String, Object> body) {
        return given().auth().oauth2(m.token()).contentType(ContentType.JSON).body(body)
                .when().post(app("/api/rides")).then();
    }

    String publicarOk(Motorista m, Map<String, Object> body) {
        return publicar(m, body).statusCode(201).extract().path("id");
    }

    ValidatableResponse pedirVaga(String token, String rideId, Map<String, Object> body) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body(body)
                .when().post(app("/api/rides/" + rideId + "/requests")).then();
    }

    static Map<String, Object> bagagem(String tipo, int c, int l, int a, double kg) {
        return Map.of("type", tipo, "quantity", 1, "lengthCm", c, "widthCm", l, "heightCm", a, "weightKg", kg);
    }

    List<Map<String, Object>> notificacoes(String token, String audience) {
        return given().auth().oauth2(token).queryParam("audience", audience)
                .when().get(app("/api/notifications")).then().statusCode(200).extract().jsonPath().getList("$");
    }

    static boolean temKind(List<Map<String, Object>> lista, String kind) {
        return lista.stream().anyMatch(n -> kind.equals(n.get("kind")));
    }
}
