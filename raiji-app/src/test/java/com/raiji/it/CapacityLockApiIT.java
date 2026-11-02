package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.service.RidePassengerService;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("Testes de integração — trava de capacidade de assentos e carga (RN03/RN12)")
class CapacityLockApiIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RidePassengerService ridePassengerService;

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

        String sub = given()
                .auth().oauth2(token)
                .when().get(app("/api/users/me"))
                .then().statusCode(200)
                .extract().path("sub");

        return new UserSetup(token, sub);
    }

    private DriverSetup criarMotorista(Integer cargoVolumeLiters) {
        UserSetup user = criarUsuarioComRole("capacity-driver", "DRIVER");

        String plate = "R" + String.format(
                "%06d",
                ThreadLocalRandom.current().nextInt(0, 1_000_000)
        );

        Map<String, Object> vehicle = new HashMap<>();
        vehicle.put("plate", plate);
        vehicle.put("type", "CARRO");
        vehicle.put("model", "Toyota Corolla");
        vehicle.put("color", "Prata");
        vehicle.put("year", 2024);
        vehicle.put("passengerSeats", 4);
        if (cargoVolumeLiters != null) {
            vehicle.put("cargoVolumeLiters", cargoVolumeLiters);
        }

        String vehicleId = given()
                .auth().oauth2(user.token())
                .contentType(ContentType.JSON)
                .body(vehicle)
                .when().post(app("/api/vehicles"))
                .then().statusCode(201)
                .extract().path("id");

        return new DriverSetup(
                user.token(),
                UUID.fromString(vehicleId),
                user.sub()
        );
    }

    private String criarCarona(DriverSetup driver, int totalSeats,
                               Map<String, Object> cargoDetails) {
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
        if (cargoDetails != null) {
            body.put("cargoDetails", cargoDetails);
        }

        return given()
                .auth().oauth2(driver.token())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .extract().path("id");
    }

    private UserSetup criarPassageiro() {
        return criarUsuarioComRole("capacity-passenger", "PASSENGER");
    }

    private static Map<String, Object> itemBagagem(
            String weightKg, int quantity,
            int lengthCm, int widthCm, int heightCm) {
        Map<String, Object> item = new HashMap<>();
        item.put("type", "mala");
        item.put("quantity", quantity);
        item.put("lengthCm", lengthCm);
        item.put("widthCm", widthCm);
        item.put("heightCm", heightCm);
        if (weightKg != null) {
            item.put("weightKg", weightKg);
        }
        item.put("description", "Mala de bordo");
        return item;
    }

    private static Map<String, Object> payloadSolicitacao(
            String nome, Map<String, Object> item) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("passengerName", nome);
        payload.put("baggage", List.of(item));
        return payload;
    }

    private String solicitarVaga(String token, String rideId, String nome,
                                 Map<String, Object> item) {
        return given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(payloadSolicitacao(nome, item))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .extract().path("id");
    }

    private void solicitarEsperando400(String token, String rideId,
                                       String nome, Map<String, Object> item) {
        given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(payloadSolicitacao(nome, item))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(400);
    }

    private Response decidir(String token, String rideId, String requestId,
                             String decision, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("decision", decision);
        payload.put("reason", reason);
        return given()
                .auth().oauth2(token)
                .contentType(ContentType.JSON)
                .body(payload)
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

    private String statusNoBanco(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ride_passengers WHERE id = CAST(? AS uuid)",
                String.class, requestId);
    }

    // ===== critérios de aceite (issue #10) =====

    @Test
    @DisplayName("RN03: duas confirmações simultâneas não estouram a lotação (1× 200, 1× 409)")
    void confirmacoesSimultaneasNaoEstouramLotacao() throws Exception {
        DriverSetup driver = criarMotorista(400);
        UserSetup pax1 = criarPassageiro();
        UserSetup pax2 = criarPassageiro();
        String rideId = criarCarona(driver, 2, null);
        String req1 = solicitarVaga(pax1.token(), rideId, "Pax Um",
                itemBagagem(null, 1, 40, 30, 20));
        String req2 = solicitarVaga(pax2.token(), rideId, "Pax Dois",
                itemBagagem(null, 1, 40, 30, 20));

        // só 1 vaga livre — os dois aceites correm simultaneamente por ela
        jdbcTemplate.update(
                "UPDATE rides SET available_seats = 1 WHERE id = CAST(? AS uuid)",
                rideId);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier largada = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (String requestId : List.of(req1, req2)) {
            futures.add(pool.submit(() -> {
                largada.await(30, TimeUnit.SECONDS); // largada simultânea
                try {
                    ridePassengerService.decide(
                            UUID.fromString(rideId),
                            UUID.fromString(requestId),
                            driver.sub(),
                            RideDecision.ACCEPT,
                            null);
                    return 200;
                } catch (ResponseStatusException ex) {
                    return ex.getStatusCode().value();
                }
            }));
        }

        pool.shutdown();
        Assertions.assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS),
                "as decisões concorrentes devem terminar");

        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : futures) {
            codes.add(f.get());
        }

        Assertions.assertEquals(1,
                codes.stream().filter(c -> c == 200).count(),
                "exatamente uma confirmação pode passar");
        Assertions.assertEquals(1,
                codes.stream().filter(c -> c == 409).count(),
                "a outra decisão deve receber 409 (sem vagas)");

        Integer vagas = jdbcTemplate.queryForObject(
                "SELECT available_seats FROM rides WHERE id = CAST(? AS uuid)",
                Integer.class, rideId);
        Assertions.assertEquals(0, vagas.intValue(),
                "vagas nunca podem ficar negativas");

        Integer confirmados = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ride_passengers "
                        + "WHERE ride_id = CAST(? AS uuid) AND status = 'CONFIRMED'",
                Integer.class, rideId);
        Assertions.assertEquals(1, confirmados.intValue(),
                "apenas uma reserva pode ficar CONFIRMED");
    }

    @Test
    @DisplayName("RN12: peso exatamente no limite é aceito (CONFIRMED)")
    void pesoNoLimiteExatoEhAceito() {
        DriverSetup driver = criarMotorista(400);
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 2, Map.of("maxWeightKg", 20));
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria",
                itemBagagem("10", 2, 40, 30, 20)); // 10 kg × 2 = 20 kg == limite

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));
    }

    @Test
    @DisplayName("RN12: peso 1 acima do limite retorna 400 e mantém PENDING")
    void pesoAcimaDoLimiteRetorna400() {
        DriverSetup driver = criarMotorista(400);
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 2, Map.of("maxWeightKg", 20));
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria",
                itemBagagem("20.5", 1, 40, 30, 20)); // 20.5 kg > 20 kg
        int vagasAntes = vagasDisponiveis(rideId);

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(400);

        Assertions.assertEquals("PENDING", statusNoBanco(requestId));
        Assertions.assertEquals(vagasAntes, vagasDisponiveis(rideId),
                "vagas não mudam quando a capacidade de carga reprova");
    }

    @Test
    @DisplayName("RN12: peso soma as bagagens já CONFIRMED na carona")
    void pesoSomaBagagensConfirmadas() {
        DriverSetup driver = criarMotorista(400);
        UserSetup pax1 = criarPassageiro();
        UserSetup pax2 = criarPassageiro();
        String rideId = criarCarona(driver, 3, Map.of("maxWeightKg", 30));

        String req1 = solicitarVaga(pax1.token(), rideId, "Pax Um",
                itemBagagem("15", 1, 40, 30, 20));
        decidir(driver.token(), rideId, req1, "ACCEPT", null)
                .then().statusCode(200);

        String req2 = solicitarVaga(pax2.token(), rideId, "Pax Dois",
                itemBagagem("16", 1, 40, 30, 20)); // 15 + 16 = 31 kg > 30 kg
        decidir(driver.token(), rideId, req2, "ACCEPT", null)
                .then().statusCode(400);

        Assertions.assertEquals("CONFIRMED", statusNoBanco(req1));
        Assertions.assertEquals("PENDING", statusNoBanco(req2));
    }

    @Test
    @DisplayName("RN12: volume exatamente no limite do veículo é aceito")
    void volumeNoLimiteExatoEhAceito() {
        DriverSetup driver = criarMotorista(100);
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 2, null);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria",
                itemBagagem(null, 2, 50, 40, 25)); // 50 L × 2 = 100 L == limite

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));
    }

    @Test
    @DisplayName("RN12: volume acima da capacidade do veículo retorna 400")
    void volumeAcimaDaCapacidadeRetorna400() {
        DriverSetup driver = criarMotorista(100);
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 2, null);
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria",
                itemBagagem(null, 2, 50, 40, 26)); // 52 L × 2 = 104 L > 100 L

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(400);

        Assertions.assertEquals("PENDING", statusNoBanco(requestId));
    }

    @Test
    @DisplayName("RN12: sem limite declarado (carona/veículo) a trava de carga é ignorada")
    void semCapacidadeDefinidaIgnoraTrava() {
        DriverSetup driver = criarMotorista(null); // veículo sem cargoVolumeLiters
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 2, null); // carona sem cargoDetails
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria",
                itemBagagem("9999", 9, 999, 99, 99));

        decidir(driver.token(), rideId, requestId, "ACCEPT", null)
                .then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));
    }

    @Test
    @DisplayName("RN12: weightKg inválido na declaração retorna 400; ausente continua válido (S2-01)")
    void weightKgInvalidoNaDeclaracaoRetorna400() {
        DriverSetup driver = criarMotorista(400);
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 2, null);

        solicitarEsperando400(passenger.token(), rideId, "Maria",
                itemBagagem("0", 1, 40, 30, 20));       // zero => @Positive
        solicitarEsperando400(passenger.token(), rideId, "Maria",
                itemBagagem("-1.5", 1, 40, 30, 20));    // negativo => @Positive
        solicitarEsperando400(passenger.token(), rideId, "Maria",
                itemBagagem("1.234", 1, 40, 30, 20));   // 3 casas => @Digits

        // sem weightKg => 201 (campo opcional — contrato S2-01 retrocompatível)
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria",
                itemBagagem(null, 1, 40, 30, 20));
        Assertions.assertNotNull(requestId);
    }

    @Test
    @DisplayName("RF18: weightKg declarado é ecoado no POST e na visão do motorista")
    void weightKgEhExpostoNasRespostas() {
        DriverSetup driver = criarMotorista(400);
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 2, null);

        given()
                .auth().oauth2(passenger.token())
                .contentType(ContentType.JSON)
                .body(payloadSolicitacao("Maria Silva",
                        itemBagagem("12.5", 1, 60, 40, 30)))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("baggage[0].weightKg", equalTo(12.5f))
                .body("baggage[0].type", equalTo("mala"));

        given()
                .auth().oauth2(driver.token())
                .when().get(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(200)
                .body("[0].baggage[0].weightKg", equalTo(12.5f));
    }
}
