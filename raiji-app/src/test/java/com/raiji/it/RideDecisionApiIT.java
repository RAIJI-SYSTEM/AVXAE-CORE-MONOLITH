package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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

@DisplayName("Testes de integração — aceite/recusa com bagagem declarada (RF08/RF19)")
class RideDecisionApiIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record UserSetup(String token, String sub) {}

    private record DriverSetup(String token, UUID vehicleId, String sub) {}

    // ===== fixtures (mesmo estilo do RideRequestApiIT) =====

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
        UserSetup user = criarUsuarioComRole("decision-driver", "DRIVER");

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
        return criarUsuarioComRole("decision-passenger", "PASSENGER");
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

    private static Map<String, Object> payloadDecisao(
            String decision, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("decision", decision);
        payload.put("reason", reason);
        return payload;
    }

    private Response decidir(String token, String rideId, String requestId,
                             String decision, String reason) {
        return given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(payloadDecisao(decision, reason))
                .when().patch(app("/api/rides/" + rideId
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
                "SELECT status, confirmed_at, cancellation_reason "
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

    // ===== critérios de aceite =====

    @Test
    @DisplayName("RF08: ACCEPT confirma, grava confirmedAt e decrementa vagas")
    void acceptConfirmaEDecrementaVagas() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        int vagasAntes = vagasDisponiveis(rideId);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(200)
                .body("id", equalTo(requestId))
                .body("status", equalTo("CONFIRMED"))
                .body("confirmedAt", notNullValue());

        Map<String, Object> row = solicitacaoNoBanco(requestId);
        Assertions.assertEquals("CONFIRMED", row.get("status").toString());
        Assertions.assertNotNull(row.get("confirmed_at"));
        Assertions.assertEquals(
                vagasAntes - 1, vagasDisponiveis(rideId));
    }

    @Test
    @DisplayName("RF19: passageiro é notificado no ACCEPT")
    void acceptNotificaPassageiro() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(200);

        Assertions.assertEquals(
                1, notificacoesDo(rideId, passenger.sub()),
                "passageiro deve receber 1 notificação de aceite");
    }

    @Test
    @DisplayName("RF08/RF19: motorista vê a bagagem e REJECT cancela com motivo, preserva vagas e notifica")
    void rejectCancelaComMotivoPreservaVagasENotifica() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");
        int vagasAntes = vagasDisponiveis(rideId);

        // RF19: o motorista visualiza a bagagem declarada ANTES de decidir
        given()
                .auth().oauth2(driver.token())
                .when().get(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo(requestId))
                .body("[0].status", equalTo("PENDING"))
                .body("[0].baggage", hasSize(1))
                .body("[0].baggage[0].type", equalTo("mala"));

        decidir(driver.token(), rideId, requestId, "REJECT",
                "A bagagem excede a capacidade do veículo.")
                .then().statusCode(200)
                .body("id", equalTo(requestId))
                .body("status", equalTo("CANCELLED"))
                .body("confirmedAt", nullValue());

        Map<String, Object> row = solicitacaoNoBanco(requestId);
        Assertions.assertEquals("CANCELLED", row.get("status").toString());
        Assertions.assertEquals(
                "A bagagem excede a capacidade do veículo.",
                row.get("cancellation_reason").toString());
        Assertions.assertEquals(
                vagasAntes, vagasDisponiveis(rideId),
                "vagas devem permanecer intactas no REJECT");
        Assertions.assertEquals(
                1, notificacoesDo(rideId, passenger.sub()),
                "passageiro deve receber 1 notificação de recusa");
    }

    @Test
    @DisplayName("RF08: REJECT sem reason retorna 400 e mantém PENDING")
    void rejectSemReasonRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");

        decidir(driver.token(), rideId, requestId, "REJECT", null)
                .then().statusCode(400);

        Assertions.assertEquals(
                "PENDING", solicitacaoNoBanco(requestId).get("status").toString());
    }

    @Test
    @DisplayName("RF08: REJECT com reason em branco retorna 400")
    void rejectComReasonEmBrancoRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");

        decidir(driver.token(), rideId, requestId, "REJECT", "   ")
                .then().statusCode(400);

        Assertions.assertEquals(
                "PENDING", solicitacaoNoBanco(requestId).get("status").toString());
    }

    @Test
    @DisplayName("RF08: decision fora do enum, nula ou ausente retorna 400")
    void decisionInvalidaRetorna400() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");

        // valor fora do enum => erro de desserialização => 400
        decidir(driver.token(), rideId, requestId, "MAYBE", null)
                .then().statusCode(400);

        // decision explicitamente nulo => @NotNull => 400
        decidir(driver.token(), rideId, requestId, null, null)
                .then().statusCode(400);

        // decision ausente no payload => @NotNull => 400
        Map<String, Object> semDecision = new HashMap<>();
        semDecision.put("reason", "motivo qualquer");
        given()
                .auth().oauth2(driver.token())
                .contentType(ContentType.JSON)
                .body(semDecision)
                .when().patch(app("/api/rides/" + rideId
                        + "/requests/" + requestId))
                .then().statusCode(400);

        Assertions.assertEquals(
                "PENDING", solicitacaoNoBanco(requestId).get("status").toString());
    }

    @Test
    @DisplayName("RF08: motorista não dono da carona recebe 403")
    void motoristaNaoDonoRetorna403() {
        DriverSetup owner = criarMotorista();
        DriverSetup intruso = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(owner, 3);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");
        int vagasAntes = vagasDisponiveis(rideId);

        decidir(intruso.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(403);

        Assertions.assertEquals(
                "PENDING", solicitacaoNoBanco(requestId).get("status").toString());
        Assertions.assertEquals(vagasAntes, vagasDisponiveis(rideId));
    }

    @Test
    @DisplayName("RF08: passageiro (role != DRIVER) não pode decidir — 403")
    void passageiroNaoPodeDecidir() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");

        // payload VÁLIDO: o 403 deve vir da role, não da validação
        decidir(passenger.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(403);
    }

    @Test
    @DisplayName("RF08: solicitação já decidida retorna 409")
    void solicitacaoJaDecididaRetorna409() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(200);
        int vagasAposAccept = vagasDisponiveis(rideId);

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(409);

        decidir(driver.token(), rideId, requestId, "REJECT", "Motivo qualquer")
                .then().statusCode(409);

        Assertions.assertEquals(
                vagasAposAccept, vagasDisponiveis(rideId),
                "vagas só podem ser decrementadas uma vez");
        Assertions.assertEquals(
                "CONFIRMED", solicitacaoNoBanco(requestId).get("status").toString());
    }

    @Test
    @DisplayName("RF08: ACCEPT sem vaga disponível retorna 409")
    void acceptSemVagaRetorna409() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3);
        String requestId =
                solicitarVaga(passenger.token(), rideId, "Maria Silva");

        // esgota as vagas diretamente no banco (cenário de corrida)
        jdbcTemplate.update(
                "UPDATE rides SET available_seats = 0 WHERE id = CAST(? AS uuid)",
                rideId);

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(409);

        Assertions.assertEquals(
                "PENDING", solicitacaoNoBanco(requestId).get("status").toString());
    }

    @Test
    @DisplayName("RF08: solicitação inexistente retorna 404")
    void solicitacaoInexistenteRetorna404() {
        DriverSetup driver = criarMotorista();
        String rideId = criarCarona(driver, 3);

        decidir(driver.token(), rideId, UUID.randomUUID().toString(),
                "ACCEPT", null)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("RF08: carona inexistente retorna 404")
    void caronaInexistenteRetorna404() {
        DriverSetup driver = criarMotorista();

        decidir(driver.token(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "ACCEPT", null)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("RF08: PATCH sem token retorna 401")
    void semTokenRetorna401() {
        given()
                .contentType(ContentType.JSON)
                .body(payloadDecisao("ACCEPT", null))
                .when().patch(app("/api/rides/" + UUID.randomUUID()
                        + "/requests/" + UUID.randomUUID()))
                .then().statusCode(401);
    }
}
