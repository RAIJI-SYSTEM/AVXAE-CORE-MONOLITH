package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testes de API (§5) — publicação de caronas (RF03 / RN02, RN03, RN08, RN10)")
class RideApiIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private record DriverSetup(String token, UUID vehicleId) {}

    private DriverSetup criarMotoristaComVeiculo(int seats) {
        String usuario = "ride-it-" + System.nanoTime();
        String userId = criarUsuario(usuario);
        atribuirRoleRealm(userId, "DRIVER");
        String token = obterToken(usuario, SENHA_NOVOS_USUARIOS);

        String placa = "R" + String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1_000_000));

        String vehicleIdStr = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of(
                        "plate", placa,
                        "type", "CARRO",
                        "model", "Toyota Corolla",
                        "color", "Prata",
                        "year", 2024,
                        "passengerSeats", seats,
                        "cargoVolumeLiters", 400))
                .when().post(app("/api/vehicles"))
                .then().statusCode(201)
                .extract().path("id");

        return new DriverSetup(token, UUID.fromString(vehicleIdStr));
    }

    private Map<String, Object> ridePayload(UUID vehicleId, BigDecimal price) {
        Map<String, Object> m = new HashMap<>();
        m.put("vehicleId", vehicleId.toString());
        m.put("type", "PASSENGER");
        m.put("originCity", "Fortaleza");
        m.put("originState", "ce");
        m.put("destinationCity", "Sobral");
        m.put("destinationState", "ce");
        m.put("departureDate", LocalDateTime.now().plusDays(1).format(ISO));
        if (price != null) m.put("pricePerPassenger", price);
        m.put("totalSeats", 2);
        return m;
    }

    @Test
    @DisplayName("CT-030: POST /api/rides carona paga → 201 + PLANNED + availableSeats = totalSeats")
    void ct030_caronaPaga() {
        DriverSetup ds = criarMotoristaComVeiculo(4);

        given().auth().oauth2(ds.token()).contentType(ContentType.JSON)
                .body(ridePayload(ds.vehicleId(), new BigDecimal("50.00")))
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("PLANNED"))
                .body("availableSeats", equalTo(2))
                .body("totalSeats", equalTo(2))
                .body("pricePerPassenger", notNullValue())
                .body("originState", equalTo("CE"))
                .body("destinationState", equalTo("CE"));
    }

    @Test
    @DisplayName("CT-031: POST /api/rides carona gratuita → 201 + pricePerPassenger = null")
    void ct031_caronaGratuita() {
        DriverSetup ds = criarMotoristaComVeiculo(4);

        given().auth().oauth2(ds.token()).contentType(ContentType.JSON)
                .body(ridePayload(ds.vehicleId(), null))
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .body("pricePerPassenger", nullValue());
    }

    @Test
    @DisplayName("CT-032: departureDate no passado → 400")
    void ct032_dataNoPassado() {
        DriverSetup ds = criarMotoristaComVeiculo(4);
        Map<String, Object> body = ridePayload(ds.vehicleId(), new BigDecimal("10"));
        body.put("departureDate", LocalDateTime.now().minusHours(1).format(ISO));

        given().auth().oauth2(ds.token()).contentType(ContentType.JSON)
                .body(body).when().post(app("/api/rides"))
                .then().statusCode(400);
    }

    @Test
    @DisplayName("CT-033 (RN10): departureDate > 3 meses → 400")
    void ct033_dataAcimaDe3Meses() {
        DriverSetup ds = criarMotoristaComVeiculo(4);
        Map<String, Object> body = ridePayload(ds.vehicleId(), new BigDecimal("10"));
        body.put("departureDate", LocalDateTime.now().plusMonths(4).format(ISO));

        given().auth().oauth2(ds.token()).contentType(ContentType.JSON)
                .body(body).when().post(app("/api/rides"))
                .then().statusCode(400);
    }

    @Test
    @DisplayName("CT-034 (RN03): totalSeats > assentos do veículo → 400")
    void ct034_assentosExcedidos() {
        DriverSetup ds = criarMotoristaComVeiculo(2);
        Map<String, Object> body = ridePayload(ds.vehicleId(), new BigDecimal("10"));
        body.put("totalSeats", 5);

        given().auth().oauth2(ds.token()).contentType(ContentType.JSON)
                .body(body).when().post(app("/api/rides"))
                .then().statusCode(400);
    }

    @Test
    @DisplayName("CT-035: vehicleId de outro motorista → 403")
    void ct035_veiculoDeOutroMotorista() {
        DriverSetup a = criarMotoristaComVeiculo(4);
        DriverSetup b = criarMotoristaComVeiculo(4);

        given().auth().oauth2(a.token()).contentType(ContentType.JSON)
                .body(ridePayload(b.vehicleId(), new BigDecimal("10")))
                .when().post(app("/api/rides"))
                .then().statusCode(403);
    }

    @Test
    @DisplayName("CT-036: CARGO sem cargoDetails → 400")
    void ct036_cargoSemDetalhes() {
        DriverSetup ds = criarMotoristaComVeiculo(4);
        Map<String, Object> body = ridePayload(ds.vehicleId(), new BigDecimal("10"));
        body.put("type", "CARGO");

        given().auth().oauth2(ds.token()).contentType(ContentType.JSON)
                .body(body).when().post(app("/api/rides"))
                .then().statusCode(400);
    }

    @Test
    @DisplayName("CT-037 (RN08): PATCH /api/rides/{id}/price em PLANNED → 200 e preço alterado")
    void ct037_alterarPreco() {
        DriverSetup ds = criarMotoristaComVeiculo(4);

        String rideId = given().auth().oauth2(ds.token()).contentType(ContentType.JSON)
                .body(ridePayload(ds.vehicleId(), new BigDecimal("10.00")))
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .extract().path("id");

        given().auth().oauth2(ds.token()).contentType(ContentType.JSON)
                .body("{\"pricePerPassenger\": 99.90}")
                .when().patch(app("/api/rides/" + rideId + "/price"))
                .then().statusCode(200)
                .body("pricePerPassenger", notNullValue());
    }

    @Test
    @DisplayName("CT-038: dados incompletos → 400 (RN02)")
    void ct038_dadosIncompletos() {
        DriverSetup ds = criarMotoristaComVeiculo(4);

        given().auth().oauth2(ds.token()).contentType(ContentType.JSON)
                .body("{}")
                .when().post(app("/api/rides"))
                .then().statusCode(400);
    }
}
