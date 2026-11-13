package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S2-01 / RF18 — Declaração de bagagem na solicitação de vaga.
 *
 * Cobre os critérios de aceite:
 *  - solicitação SEM baggage            -> 400
 *  - solicitação com baggage VAZIO      -> 400
 *  - quantity <= 0                      -> 400
 *  - dimensões <= 0                     -> 400
 *  - type fora do enum                  -> 400
 *  - bagagem persiste e é recuperada no detalhe da solicitação
 *  - motorista vê a bagagem ANTES de aceitar/recusar
 */
@DisplayName("Testes de integração — declaração de bagagem (S2-01/RF18)")
class BaggageApiIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record UserSetup(String token, String sub) {}

    private record DriverSetup(String token, UUID vehicleId, String sub) {}

    // -------------------------------------------------------------------------
    // Setup (mesmo padrão de RideRequestApiIT)
    // -------------------------------------------------------------------------

    private UserSetup criarUsuarioComRole(String prefixo, String role) {
        String username = prefixo + "-" + System.nanoTime();
        String userId = criarUsuario(username);

        if (role != null) {
            atribuirRoleRealm(userId, role);
        }

        String token = obterToken(username, SENHA_NOVOS_USUARIOS);

        String sub = given()
                .auth().oauth2(token)
                .when().get(app("/api/users/me"))
                .then().statusCode(200)
                .extract().path("sub");

        return new UserSetup(token, sub);
    }

    private DriverSetup criarMotorista() {
        UserSetup user = criarUsuarioComRole("baggage-driver", "DRIVER");

        String plate = "B" + String.format(
                "%06d",
                ThreadLocalRandom.current().nextInt(0, 1_000_000)
        );

        String vehicleId = given()
                .auth().oauth2(user.token())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "plate", plate,
                        "type", "CARRO",
                        "model", "Honda Civic",
                        "color", "Preto",
                        "year", 2023,
                        "passengerSeats", 4,
                        "cargoVolumeLiters", 400
                ))
                .when().post(app("/api/vehicles"))
                .then().statusCode(201)
                .extract().path("id");

        return new DriverSetup(
                user.token(),
                UUID.fromString(vehicleId),
                user.sub()
        );
    }

    private String criarCarona(DriverSetup driver) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", driver.vehicleId().toString());
        body.put("type", "PASSENGER");
        body.put("originCity", "Fortaleza");
        body.put("originState", "CE");
        body.put("destinationCity", "Sobral");
        body.put("destinationState", "CE");
        body.put("departureDate", LocalDateTime.now()
                .plusDays(1).format(ISO));
        body.put("totalSeats", 3);

        return given()
                .auth().oauth2(driver.token())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .extract().path("id");
    }

    private UserSetup criarPassageiro() {
        return criarUsuarioComRole("baggage-passenger", "PASSENGER");
    }

    // -------------------------------------------------------------------------
    // Helpers de payload
    // -------------------------------------------------------------------------

    private static Map<String, Object> item(
            String type, int quantity, int lengthCm, int widthCm, int heightCm,
            String description
    ) {
        Map<String, Object> item = new HashMap<>();
        item.put("type", type);
        item.put("quantity", quantity);
        item.put("lengthCm", lengthCm);
        item.put("widthCm", widthCm);
        item.put("heightCm", heightCm);
        item.put("description", description);
        return item;
    }

    private static Map<String, Object> itemValido() {
        return item("mala", 1, 60, 40, 30, "Mala de bordo");
    }

    private static Map<String, Object> payload(String nome, List<Map<String, Object>> baggage) {
        Map<String, Object> body = new HashMap<>();
        body.put("passengerName", nome);
        if (baggage != null) {
            body.put("baggage", baggage);
        }
        return body;
    }

    // -------------------------------------------------------------------------
    // Critérios de aceite — casos de 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("bagagem opcional: solicitação SEM baggage é criada (201)")
    void solicitacaoSemBaggageEhCriada() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", null))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201);
    }

    @Test
    @DisplayName("bagagem opcional: baggage VAZIO = sem bagagem (201)")
    void solicitacaoComBaggageVazioEhCriada() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", List.of()))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201).body("baggage", org.hamcrest.Matchers.hasSize(0));
    }

    @Test
    @DisplayName("S2-01: quantity <= 0 retorna 400")
    void quantityInvalidaRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        for (int quantity : new int[]{0, -1}) {
            given()
                    .auth().oauth2(passenger.token())
                    .contentType(ContentType.JSON)
                    .body(payload("Maria Silva",
                            List.of(item("mala", quantity, 60, 40, 30, "Mala"))))
                    .when().post(app("/api/rides/" + rideId + "/requests"))
                    .then().statusCode(400);
        }
    }

    @Test
    @DisplayName("S2-01: dimensões <= 0 retornam 400")
    void dimensoesInvalidasRetornam400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        List<Map<String, Object>> casosInvalidos = List.of(
                item("mala", 1, 0, 40, 30, "comprimento zero"),
                item("mala", 1, 60, -5, 30, "largura negativa"),
                item("mala", 1, 60, 40, 0, "altura zero")
        );

        for (Map<String, Object> caso : casosInvalidos) {
            given()
                    .auth().oauth2(passenger.token())
                    .contentType(ContentType.JSON)
                    .body(payload("Maria Silva", List.of(caso)))
                    .when().post(app("/api/rides/" + rideId + "/requests"))
                    .then().statusCode(400);
        }
    }

    @Test
    @DisplayName("S2-01: type fora do enum retorna 400")
    void typeForaDoEnumRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva",
                        List.of(item("geladeira", 1, 60, 40, 30, "Fora do enum"))))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(400);
    }

    // -------------------------------------------------------------------------
    // Critérios de aceite — persistência e visão do motorista
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("S2-01: bagagem persiste e é retornada na solicitação")
    void bagagemPersisteEhRetornada() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", List.of(itemValido())))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("PENDING"))
                .body("baggage", hasSize(1))
                .body("baggage[0].type", equalTo("mala"))
                .body("baggage[0].quantity", equalTo(1))
                .body("baggage[0].lengthCm", equalTo(60))
                .body("baggage[0].widthCm", equalTo(40))
                .body("baggage[0].heightCm", equalTo(30))
                .body("baggage[0].description", equalTo("Mala de bordo"));

        // persistência real: tabela baggage com FK para ride_passengers
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baggage b "
                        + "JOIN ride_passengers rp ON b.ride_passenger_id = rp.id "
                        + "WHERE rp.ride_id = CAST(? AS uuid)",
                Integer.class, rideId);
        org.junit.jupiter.api.Assertions.assertEquals(1, total);

        Map<String, Object> linha = jdbcTemplate.queryForMap(
                "SELECT b.type, b.quantity, b.length_cm, b.width_cm, b.height_cm, b.description "
                        + "FROM baggage b "
                        + "JOIN ride_passengers rp ON b.ride_passenger_id = rp.id "
                        + "WHERE rp.ride_id = CAST(? AS uuid)",
                rideId);
        org.junit.jupiter.api.Assertions.assertEquals("MALA", linha.get("type"));
        org.junit.jupiter.api.Assertions.assertEquals(1, linha.get("quantity"));
        org.junit.jupiter.api.Assertions.assertEquals(60, linha.get("length_cm"));
        org.junit.jupiter.api.Assertions.assertEquals(40, linha.get("width_cm"));
        org.junit.jupiter.api.Assertions.assertEquals(30, linha.get("height_cm"));
        org.junit.jupiter.api.Assertions.assertEquals("Mala de bordo", linha.get("description"));
    }

    @Test
    @DisplayName("S2-01: motorista vê a bagagem ANTES de aceitar/recusar")
    void motoristaVeBagagemAntesDeDecidir() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", List.of(itemValido())))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201);

        // solicitação ainda PENDING (motorista não decidiu) e a bagagem visível
        given()
                .auth().oauth2(driver.token())
                .when().get(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].status", equalTo("PENDING"))
                .body("[0].passengerName", equalTo("Maria Silva"))
                .body("[0].baggage", hasSize(1))
                .body("[0].baggage[0].type", equalTo("mala"))
                .body("[0].baggage[0].quantity", equalTo(1))
                .body("[0].baggage[0].lengthCm", equalTo(60))
                .body("[0].baggage[0].widthCm", equalTo(40))
                .body("[0].baggage[0].heightCm", equalTo(30))
                .body("[0].baggage[0].description", equalTo("Mala de bordo"));
    }

    @Test
    @DisplayName("S2-01: múltiplos itens de bagagem persistem")
    void multiplosItensPersistem() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        List<Map<String, Object>> itens = new ArrayList<>();
        itens.add(item("mochila", 2, 45, 30, 20, "Mochila escolar"));
        itens.add(item("caixa", 1, 50, 40, 40, "Caixa fechada"));

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva", itens))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("baggage", hasSize(2));

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baggage b "
                        + "JOIN ride_passengers rp ON b.ride_passenger_id = rp.id "
                        + "WHERE rp.ride_id = CAST(? AS uuid)",
                Integer.class, rideId);
        org.junit.jupiter.api.Assertions.assertEquals(2, total);
    }

    @Test
    @DisplayName("S2-01: type é case-insensitive na entrada e minúsculo na saída")
    void typeCaseInsensitiveEntradaMinusculaSaida() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payload("Maria Silva",
                        List.of(item("SACOLA", 1, 35, 25, 15, "Sacola reforçada"))))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("baggage[0].type", equalTo("sacola"));
    }
}
