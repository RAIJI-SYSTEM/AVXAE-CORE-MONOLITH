
package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.hasSize;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("Testes de integração — solicitações de carona (RF06)")
class RideRequestApiIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record UserSetup(String token, String sub) {}

    private record DriverSetup(String token, UUID vehicleId, String sub) {}

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
        UserSetup user = criarUsuarioComRole("request-driver", "DRIVER");

        String plate = "R" + String.format(
                "%06d",
                ThreadLocalRandom.current().nextInt(0, 1_000_000)
        );

        String vehicleId = given()
                .auth().oauth2(user.token())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "plate", plate,
                        "type", "CARRO",
                        "model", "Toyota Corolla",
                        "color", "Prata",
                        "year", 2024,
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
        body.put("totalSeats", 2);

        return given()
                .auth().oauth2(driver.token())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .extract().path("id");
    }

    private UserSetup criarPassageiro() {
        return criarUsuarioComRole("request-passenger", "PASSENGER");
    }

    private void solicitar(String token, String rideId, String nome) {
        given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", nome))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201);
    }

    @Test
    @DisplayName("RF06: passageiro solicita vaga e recebe PENDING")
    void passageiroSolicitaVaga() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", "Maria Silva"))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("rideId", equalTo(rideId))
                .body("passengerSub", equalTo(passenger.sub()))
                .body("passengerName", equalTo("Maria Silva"))
                .body("status", equalTo("PENDING"))
                .body("joinedAt", notNullValue());

        Integer notifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications " +
                        "WHERE ride_id = CAST(? AS uuid) AND recipient_sub = ?",
                Integer.class,
                rideId,
                driver.sub()
        );

        org.junit.jupiter.api.Assertions.assertEquals(1, notifications);
    }

    @Test
    @DisplayName("RF06: carona inexistente retorna 404")
    void caronaInexistente() {
        UserSetup passenger = criarPassageiro();

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", "Maria Silva"))
                .when().post(app("/api/rides/" + UUID.randomUUID() + "/requests"))
                .then().statusCode(404);
    }

    @Test
    @DisplayName("RF06: passageiro não pode solicitar duas vezes")
    void passageiroNaoPodeSolicitarDuasVezes() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        solicitar(passenger.token(), rideId, "Maria Silva");

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", "Maria Silva"))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(409);
    }

    @Test
    @DisplayName("RF06: carona sem vagas retorna 409")
    void caronaSemVagas() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        jdbcTemplate.update(
                "UPDATE rides SET available_seats = 0 WHERE id = CAST(? AS uuid)",
                rideId
        );

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", "Maria Silva"))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(409);
    }

    @Test
    @DisplayName("RF06: motorista consulta solicitações da própria carona")
    void motoristaConsultaSolicitacoes() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        solicitar(passenger.token(), rideId, "Maria Silva");

        given()
                .auth().oauth2(driver.token())
                .when().get(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].passengerName", equalTo("Maria Silva"))
                .body("[0].status", equalTo("PENDING"));
    }

    @Test
    @DisplayName("RF06: motorista não consulta solicitações de outra carona")
    void motoristaNaoPodeConsultarCaronaDeOutro() {
        DriverSetup owner = criarMotorista();
        DriverSetup other = criarMotorista();

        String rideId = criarCarona(owner);

        given()
                .auth().oauth2(other.token())
                .when().get(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(403);
    }

    @Test
    @DisplayName("RF06: usuário sem role PASSENGER não solicita vaga")
    void usuarioSemRolePassengerNaoSolicita() {
        DriverSetup driver = criarMotorista();
        UserSetup user = criarUsuarioComRole("request-user", null);
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(user.token())
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", "Maria Silva"))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(403);
    }

    @Test
    @DisplayName("RF06: nome vazio retorna 400")
    void nomeVazioRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", " "))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(400);
    }

    @Test
    @DisplayName("RF06: requisição sem token retorna 401")
    void semTokenRetorna401() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", "Maria Silva"))
                .when().post(app("/api/rides/" + UUID.randomUUID() + "/requests"))
                .then().statusCode(401);
    }
}
