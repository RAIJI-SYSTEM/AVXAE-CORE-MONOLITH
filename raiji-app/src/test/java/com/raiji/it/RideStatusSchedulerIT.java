package com.raiji.it;

import static io.restassured.RestAssured.given;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.raiji.rides.scheduler.RideStatusScheduler;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S2-06 / RF10 + RN05 — teste de integração do status automático.
 *
 * <p>"Avançar o relógio" é simulado de forma determinística movendo o
 * {@code departure_date} da carona para o passado via banco (equivalente a
 * {@code now() > departureDate}) e disparando o {@link RideStatusScheduler}
 * manualmente (o job cron fica com intervalo enorme nos testes — ver
 * {@code RaijiTestSupport}). O limite exato {@code departureDate == now()} é
 * coberto no teste unitário com {@code Clock} fixo.
 *
 * <p>Critérios de aceite: após a partida o status vira {@code IN_PROGRESS}, o
 * POST de solicitação retorna 409, o PATCH de aceite retorna 409, caronas
 * futuras não são afetadas e o scheduler é idempotente.
 */
@DisplayName("Testes de integração — bloqueio no horário de saída e status automático (S2-06/RF10)")
class RideStatusSchedulerIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RideStatusScheduler scheduler;

    private record UserSetup(String token, String sub) {}

    private record DriverSetup(String token, UUID vehicleId, String sub) {}

    // ===== fixtures =====

    private UserSetup criarUsuarioComRole(String prefixo, String role) {
        String username = prefixo + "-" + System.nanoTime();
        String userId = criarUsuario(username);
        if (role != null) {
            atribuirRoleRealm(userId, role);
        }
        String token = obterToken(username, SENHA_NOVOS_USUARIOS);
        String sub = given().auth().oauth2(token)
                .when().get(app("/api/users/me"))
                .then().statusCode(200).extract().path("sub");
        return new UserSetup(token, sub);
    }

    private DriverSetup criarMotorista() {
        UserSetup user = criarUsuarioComRole("sched-driver", "DRIVER");
        String plate = "S" + String.format(
                "%06d", ThreadLocalRandom.current().nextInt(0, 1_000_000));
        String vehicleId = given().auth().oauth2(user.token())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "plate", plate, "type", "CARRO", "model", "Fiat Toro",
                        "color", "Branco", "year", 2024, "passengerSeats", 4,
                        "cargoVolumeLiters", 400))
                .when().post(app("/api/vehicles"))
                .then().statusCode(201).extract().path("id");
        return new DriverSetup(user.token(), UUID.fromString(vehicleId), user.sub());
    }

    private UserSetup criarPassageiro() {
        return criarUsuarioComRole("sched-passenger", "PASSENGER");
    }

    private String criarCarona(DriverSetup driver, int totalSeats) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", driver.vehicleId().toString());
        body.put("type", "PASSENGER");
        body.put("originCity", "Fortaleza");
        body.put("originState", "CE");
        body.put("destinationCity", "Sobral");
        body.put("destinationState", "CE");
        body.put("departureDate", LocalDateTime.now().plusDays(1).format(ISO));
        body.put("totalSeats", totalSeats);
        return given().auth().oauth2(driver.token())
                .contentType(ContentType.JSON).body(body)
                .when().post(app("/api/rides"))
                .then().statusCode(201).extract().path("id");
    }

    private static Map<String, Object> payloadSolicitacao(String nome) {
        Map<String, Object> item = new HashMap<>();
        item.put("type", "mala");
        item.put("quantity", 1);
        item.put("lengthCm", 60);
        item.put("widthCm", 40);
        item.put("heightCm", 30);
        Map<String, Object> payload = new HashMap<>();
        payload.put("passengerName", nome);
        payload.put("baggage", List.of(item));
        return payload;
    }

    private String solicitarVaga(String token, String rideId, String nome) {
        return given().auth().oauth2(token)
                .contentType(ContentType.JSON).body(payloadSolicitacao(nome))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201).extract().path("id");
    }

    // ===== helpers de banco =====

    private void moverPartidaParaOPassado(String rideId) {
        jdbcTemplate.update(
                "UPDATE rides SET departure_date = ? WHERE id = CAST(? AS uuid)",
                LocalDateTime.now().minusMinutes(2), rideId);
    }

    private String statusDaCarona(String rideId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM rides WHERE id = CAST(? AS uuid)",
                String.class, rideId);
    }

    // ===== critérios de aceite (issue #13) =====

    @Test
    @DisplayName("RN05: após departureDate o scheduler muda PLANNED -> IN_PROGRESS")
    void schedulerPromoveCaronaVencida() {
        DriverSetup driver = criarMotorista();
        String rideId = criarCarona(driver, 3);
        Assertions.assertEquals("PLANNED", statusDaCarona(rideId));

        moverPartidaParaOPassado(rideId);          // "avança o relógio"
        scheduler.promoteDueRides();               // dispara o job manualmente

        Assertions.assertEquals("IN_PROGRESS", statusDaCarona(rideId),
                "a carona vencida deve ser promovida automaticamente");
    }

    @Test
    @DisplayName("RF10: após a partida, POST de solicitação retorna 409")
    void postRetorna409AposPartida() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);

        moverPartidaParaOPassado(rideId);
        scheduler.promoteDueRides();

        given().auth().oauth2(passenger.token())
                .contentType(ContentType.JSON).body(payloadSolicitacao("Maria"))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(409);
    }

    @Test
    @DisplayName("RF10: após a partida, PATCH de aceite retorna 409 (carona não PLANNED)")
    void patchAcceptRetorna409AposPartida() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria");

        moverPartidaParaOPassado(rideId);
        scheduler.promoteDueRides(); // agora IN_PROGRESS

        given().auth().oauth2(driver.token())
                .contentType(ContentType.JSON).body(Map.of("decision", "ACCEPT"))
                .when().patch(app("/api/rides/" + rideId + "/requests/" + requestId))
                .then().statusCode(409);
    }

    @Test
    @DisplayName("RN05: caronas futuras NÃO são afetadas pelo scheduler")
    void caronasFuturasNaoSaoAfetadas() {
        DriverSetup driver = criarMotorista();
        String rideId = criarCarona(driver, 3); // departure = now + 1 dia

        scheduler.promoteDueRides();

        Assertions.assertEquals("PLANNED", statusDaCarona(rideId),
                "carona futura permanece PLANNED");
    }

    @Test
    @DisplayName("RN05: scheduler é idempotente (2ª execução mantém IN_PROGRESS)")
    void schedulerEhIdempotente() {
        DriverSetup driver = criarMotorista();
        String rideId = criarCarona(driver, 3);

        moverPartidaParaOPassado(rideId);
        scheduler.promoteDueRides();
        Assertions.assertEquals("IN_PROGRESS", statusDaCarona(rideId));

        scheduler.promoteDueRides(); // roda de novo
        Assertions.assertEquals("IN_PROGRESS", statusDaCarona(rideId),
                "estado estável — sem duplo processamento");
    }
}
