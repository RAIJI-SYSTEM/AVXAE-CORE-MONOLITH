package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S2-04 / RF09 + RN06 — cancelamento de reserva e liberação de vaga.
 *
 * <p>Cobre TODOS os critérios de aceite da issue #11:
 * <ul>
 *   <li>DELETE em reserva CONFIRMED libera a vaga na hora (availableSeats++).</li>
 *   <li>DELETE em reserva PENDING não altera availableSeats.</li>
 *   <li>Reserva muda para CANCELLED com cancellationReason e data (cancelled_at).</li>
 *   <li>Motorista é notificado.</li>
 *   <li>Cancelamento após início da viagem (status != PLANNED) retorna 409.</li>
 *   <li>Passageiro tentando cancelar reserva de outro retorna 403.</li>
 *   <li>Reserva já cancelada retorna 409.</li>
 * </ul>
 */
@DisplayName("Testes de integração — cancelamento de reserva e liberação de vaga (S2-04/RF09)")
class RideCancelApiIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record UserSetup(String token, String sub) {}

    private record DriverSetup(String token, UUID vehicleId, String sub) {}

    // ===== fixtures (mesmo padrão de RideDecisionApiIT) =====

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
        UserSetup user = criarUsuarioComRole("cancel-driver", "DRIVER");

        String plate = "C" + String.format(
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

    private String criarCarona(DriverSetup driver, int totalSeats) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", driver.vehicleId().toString());
        body.put("type", "PASSENGER");
        body.put("originCity", "Fortaleza");
        body.put("originState", "CE");
        body.put("destinationCity", "Sobral");
        body.put("destinationState", "CE");
        body.put("departureDate", LocalDateTime.now()
                .plusDays(1).format(ISO));
        body.put("totalSeats", totalSeats);

        return given()
                .auth().oauth2(driver.token())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .extract().path("id");
    }

    private UserSetup criarPassageiro() {
        return criarUsuarioComRole("cancel-passenger", "PASSENGER");
    }

    private static Map<String, Object> itemBagagem() {
        Map<String, Object> item = new HashMap<>();
        item.put("type", "mala");
        item.put("quantity", 1);
        item.put("lengthCm", 60);
        item.put("widthCm", 40);
        item.put("heightCm", 30);
        item.put("description", "Mala de bordo");
        return item;
    }

    private static Map<String, Object> payloadSolicitacao(String nome) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("passengerName", nome);
        payload.put("baggage", List.of(itemBagagem()));
        return payload;
    }

    private String solicitarVaga(String token, String rideId, String nome) {
        return given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(payloadSolicitacao(nome))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .extract().path("id");
    }

    /** Motorista aceita a solicitação (ACCEPT) => CONFIRMED + availableSeats--. */
    private void aceitar(String driverToken, String rideId, String requestId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("decision", "ACCEPT");
        given()
                .auth().oauth2(driverToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .when().patch(app("/api/rides/" + rideId
                        + "/requests/" + requestId))
                .then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));
    }

    private Response cancelar(String token, String rideId, String requestId,
                              String reason) {
        var spec = given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON);
        if (reason != null) {
            spec = spec.body(Map.of("reason", reason));
        }
        return spec.when().delete(app("/api/rides/" + rideId
                + "/requests/" + requestId));
    }

    // ===== helpers de banco =====

    private int vagasDisponiveis(String rideId) {
        Integer vagas = jdbcTemplate.queryForObject(
                "SELECT available_seats FROM rides WHERE id = CAST(? AS uuid)",
                Integer.class, rideId);
        return vagas == null ? -1 : vagas;
    }

    private Map<String, Object> solicitacaoNoBanco(String requestId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, confirmed_at, cancellation_reason, cancelled_at "
                        + "FROM ride_passengers WHERE id = CAST(? AS uuid)",
                requestId);
    }

    private int notificacoesDo(String rideId, String recipientSub) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications "
                        + "WHERE ride_id = CAST(? AS uuid) AND recipient_sub = ?",
                Integer.class, rideId, recipientSub);
        return total == null ? 0 : total;
    }

    // ===== critérios de aceite (issue #11) =====

    @Test
    @DisplayName("RF09: DELETE em reserva CONFIRMED libera a vaga na hora (availableSeats++)")
    void deleteEmConfirmadaLiberaVaga() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");
        aceitar(driver.token(), rideId, requestId);

        int vagasAposAccept = vagasDisponiveis(rideId);

        cancelar(passenger.token(), rideId, requestId, "Imprevisto pessoal")
                .then().statusCode(200)
                .body("id", equalTo(requestId))
                .body("status", equalTo("CANCELLED"))
                .body("seatReleased", equalTo(true))
                .body("cancelledAt", notNullValue())
                .body("cancellationReason", equalTo("Imprevisto pessoal"));

        Assertions.assertEquals(
                vagasAposAccept + 1, vagasDisponiveis(rideId),
                "a vaga deve ser liberada imediatamente no cancelamento de CONFIRMED");
    }

    @Test
    @DisplayName("RF09: DELETE em reserva PENDING NÃO altera availableSeats")
    void deleteEmPendingNaoAlteraVagas() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");

        int vagasAntes = vagasDisponiveis(rideId);

        cancelar(passenger.token(), rideId, requestId, "Desisti da viagem")
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("seatReleased", equalTo(false));

        Assertions.assertEquals(
                vagasAntes, vagasDisponiveis(rideId),
                "cancelar PENDING não mexe no contador de vagas");
    }

    @Test
    @DisplayName("RF09: reserva muda para CANCELLED com cancellationReason e data (cancelled_at)")
    void reservaMudaParaCancelledComReasonEData() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");
        aceitar(driver.token(), rideId, requestId);

        cancelar(passenger.token(), rideId, requestId, "Imprevisto pessoal")
                .then().statusCode(200);

        Map<String, Object> row = solicitacaoNoBanco(requestId);
        Assertions.assertEquals("CANCELLED", row.get("status").toString());
        Assertions.assertEquals(
                "Imprevisto pessoal", row.get("cancellation_reason").toString());
        Assertions.assertNotNull(row.get("cancelled_at"),
                "a data do cancelamento deve ser persistida");
    }

    @Test
    @DisplayName("RF09: motorista é notificado no cancelamento")
    void motoristaEhNotificado() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");
        aceitar(driver.token(), rideId, requestId);

        int antes = notificacoesDo(rideId, driver.sub());

        cancelar(passenger.token(), rideId, requestId, "Imprevisto pessoal")
                .then().statusCode(200);

        Assertions.assertEquals(
                antes + 1, notificacoesDo(rideId, driver.sub()),
                "o motorista deve receber 1 notificação de cancelamento");
    }

    @Test
    @DisplayName("RN06: cancelamento após início da viagem (status != PLANNED) retorna 409")
    void cancelamentoAposInicioRetorna409() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");
        aceitar(driver.token(), rideId, requestId);

        // simula o scheduler (S2-06): viagem iniciada
        jdbcTemplate.update(
                "UPDATE rides SET status = 'IN_PROGRESS' WHERE id = CAST(? AS uuid)",
                rideId);

        cancelar(passenger.token(), rideId, requestId, "tarde demais")
                .then().statusCode(409);

        Assertions.assertEquals(
                "CONFIRMED", solicitacaoNoBanco(requestId).get("status").toString(),
                "a reserva não pode ser cancelada após o início da viagem");
    }

    @Test
    @DisplayName("RF09: passageiro tentando cancelar reserva de OUTRO retorna 403")
    void passageiroCancelandoReservaDeOutroRetorna403() {
        DriverSetup driver = criarMotorista();
        UserSetup dono = criarPassageiro();
        UserSetup intruso = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(dono.token(), rideId, "Maria Silva");
        aceitar(driver.token(), rideId, requestId);

        int vagasAntes = vagasDisponiveis(rideId);

        cancelar(intruso.token(), rideId, requestId, "não é minha")
                .then().statusCode(403);

        Assertions.assertEquals(
                "CONFIRMED", solicitacaoNoBanco(requestId).get("status").toString());
        Assertions.assertEquals(vagasAntes, vagasDisponiveis(rideId));
    }

    @Test
    @DisplayName("RF09: reserva já cancelada retorna 409")
    void reservaJaCanceladaRetorna409() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");

        cancelar(passenger.token(), rideId, requestId, "primeiro cancelamento")
                .then().statusCode(200);

        cancelar(passenger.token(), rideId, requestId, "segundo cancelamento")
                .then().statusCode(409);
    }

    @Test
    @DisplayName("RF09: motorista dono da carona pode cancelar a reserva do passageiro")
    void motoristaDonoPodeCancelar() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");
        aceitar(driver.token(), rideId, requestId);

        int vagasAposAccept = vagasDisponiveis(rideId);

        cancelar(driver.token(), rideId, requestId, "Veículo em manutenção")
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("seatReleased", equalTo(true));

        Assertions.assertEquals(vagasAposAccept + 1, vagasDisponiveis(rideId));
    }

    @Test
    @DisplayName("RF09: cancelamento SEM motivo (corpo opcional) retorna 200")
    void cancelamentoSemMotivoRetorna200() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");

        // DELETE sem corpo — o reason é opcional
        given()
                .auth().oauth2(passenger.token())
                .when().delete(app("/api/rides/" + rideId
                        + "/requests/" + requestId))
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("seatReleased", equalTo(false));
    }

    @Test
    @DisplayName("RF09: reserva inexistente retorna 404")
    void reservaInexistenteRetorna404() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);

        cancelar(passenger.token(), rideId, UUID.randomUUID().toString(), "x")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("RF09: carona inexistente retorna 404")
    void caronaInexistenteRetorna404() {
        UserSetup passenger = criarPassageiro();

        cancelar(passenger.token(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "x")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("RF09: DELETE sem token retorna 401")
    void semTokenRetorna401() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "sem token"))
                .when().delete(app("/api/rides/" + UUID.randomUUID()
                        + "/requests/" + UUID.randomUUID()))
                .then().statusCode(401);
    }
}
