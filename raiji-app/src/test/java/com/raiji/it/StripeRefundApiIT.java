package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import com.raiji.rides.service.stripe.StripeRefundGateway;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * S2-05 / RN04 — estorno automático no cancelamento de caronas pagas.
 *
 * <p>Usa um {@link StripeRefundGateway} FAKE (@Primary) — determinístico, sem
 * rede e sem chave real — para validar o fluxo de ponta a ponta:
 * <ul>
 *   <li>carona paga cancelada =&gt; chama o "Stripe" e registra ESTORNO_SUCESSO
 *       com o id do refund;</li>
 *   <li>carona gratuita =&gt; NÃO chama o Stripe;</li>
 *   <li>falha no Stripe =&gt; NÃO impede o cancelamento e registra
 *       ESTORNO_FALHOU.</li>
 * </ul>
 *
 * <p>A propriedade {@code raiji.stripe.api-key} é definida com um valor
 * {@code sk_test_*} fictício apenas para marcar o Stripe como "configurado";
 * nenhuma chamada real ocorre (o gateway é fake). Para um teste contra o
 * sandbox real, ver {@code StripeSandboxRefundIT} (habilitado só com
 * {@code STRIPE_TEST_API_KEY}).
 */
@DisplayName("Testes de integração — estorno automático de caronas pagas (S2-05/RN04)")
@TestPropertySource(properties = {
        "raiji.stripe.api-key=sk_test_IT_FAKE_KEY"
})
@Import(StripeRefundApiIT.FakeStripeConfig.class)
class StripeRefundApiIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeStripeGateway fakeGateway;

    // ----- gateway fake (controllável: sucesso/falha + registro de chamadas) -----

    @TestConfiguration
    static class FakeStripeConfig {
        @Bean
        @Primary
        FakeStripeGateway fakeStripeGateway() {
            return new FakeStripeGateway();
        }
    }

    static class FakeStripeGateway implements StripeRefundGateway {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean fail = false;

        void reset() {
            calls.clear();
            fail = false;
        }

        @Override
        public String createRefund(String paymentIntentId, long amountCents) {
            calls.add(paymentIntentId + ":" + amountCents);
            if (fail) {
                throw new RuntimeException("Erro simulado no sandbox Stripe");
            }
            return "re_IT_" + paymentIntentId;
        }
    }

    @BeforeEach
    void resetGateway() {
        fakeGateway.reset();
    }

    // ===== fixtures =====

    private record UserSetup(String token, String sub) {}

    private record DriverSetup(String token, UUID vehicleId, String sub) {}

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
        UserSetup user = criarUsuarioComRole("refund-driver", "DRIVER");
        String plate = "P" + String.format(
                "%06d", ThreadLocalRandom.current().nextInt(0, 1_000_000));
        String vehicleId = given().auth().oauth2(user.token())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "plate", plate, "type", "CARRO", "model", "Honda Civic",
                        "color", "Preto", "year", 2023, "passengerSeats", 4,
                        "cargoVolumeLiters", 400))
                .when().post(app("/api/vehicles"))
                .then().statusCode(201).extract().path("id");
        return new DriverSetup(user.token(), UUID.fromString(vehicleId), user.sub());
    }

    private UserSetup criarPassageiro() {
        return criarUsuarioComRole("refund-passenger", "PASSENGER");
    }

    /** Carona com preço (paga) ou gratuita (price == null). */
    private String criarCarona(DriverSetup driver, int totalSeats, String price) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", driver.vehicleId().toString());
        body.put("type", "PASSENGER");
        body.put("originCity", "Fortaleza");
        body.put("originState", "CE");
        body.put("destinationCity", "Sobral");
        body.put("destinationState", "CE");
        body.put("departureDate", LocalDateTime.now().plusDays(1).format(ISO));
        body.put("totalSeats", totalSeats);
        if (price != null) {
            body.put("pricePerPassenger", price);
        }
        return given().auth().oauth2(driver.token())
                .contentType(ContentType.JSON).body(body)
                .when().post(app("/api/rides"))
                .then().statusCode(201).extract().path("id");
    }

    private static Map<String, Object> itemBagagem() {
        Map<String, Object> item = new HashMap<>();
        item.put("type", "mala");
        item.put("quantity", 1);
        item.put("lengthCm", 60);
        item.put("widthCm", 40);
        item.put("heightCm", 30);
        return item;
    }

    private String solicitarVaga(String token, String rideId, String nome) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("passengerName", nome);
        payload.put("baggage", List.of(itemBagagem()));
        return given().auth().oauth2(token)
                .contentType(ContentType.JSON).body(payload)
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201).extract().path("id");
    }

    /** ACCEPT informando o paymentIntentId (integração de pagamento futura). */
    private void aceitar(String driverToken, String rideId, String requestId,
                         String paymentIntentId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("decision", "ACCEPT");
        if (paymentIntentId != null) {
            payload.put("paymentIntentId", paymentIntentId);
        }
        given().auth().oauth2(driverToken)
                .contentType(ContentType.JSON).body(payload)
                .when().patch(app("/api/rides/" + rideId + "/requests/" + requestId))
                .then().statusCode(200).body("status", equalTo("CONFIRMED"));
    }

    private void cancelar(String token, String rideId, String requestId, String reason) {
        given().auth().oauth2(token)
                .contentType(ContentType.JSON).body(Map.of("reason", reason))
                .when().delete(app("/api/rides/" + rideId + "/requests/" + requestId))
                .then().statusCode(200).body("status", equalTo("CANCELLED"));
    }

    private List<Map<String, Object>> auditoriaDaReserva(String requestId) {
        return jdbcTemplate.queryForList(
                "SELECT action, details FROM audit_log "
                        + "WHERE entity_type = 'RIDE_PASSENGER' "
                        + "AND entity_id = CAST(? AS uuid)",
                requestId);
    }

    // ===== critérios de aceite (issue #12) =====

    @Test
    @DisplayName("RN04: carona PAGA cancelada gera refund e audita ESTORNO_SUCESSO com o id")
    void caronaPagaCanceladaGeraRefund() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3, "50.00");
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");
        aceitar(driver.token(), rideId, requestId, "pi_IT_pago_1");

        fakeGateway.reset();
        cancelar(passenger.token(), rideId, requestId, "Imprevisto pessoal");

        Assertions.assertEquals(
                List.of("pi_IT_pago_1:5000"), fakeGateway.calls,
                "deve estornar o valor exato (50.00 => 5000 centavos)");

        List<Map<String, Object>> audit = auditoriaDaReserva(requestId);
        Assertions.assertEquals(1, audit.size());
        Assertions.assertEquals("ESTORNO_SUCESSO", audit.get(0).get("action"));
        Assertions.assertTrue(
                String.valueOf(audit.get(0).get("details")).contains("re_IT_pi_IT_pago_1"),
                "a auditoria deve conter o id do refund");
    }

    @Test
    @DisplayName("RN04: carona GRATUITA não chama o Stripe")
    void caronaGratuitaNaoChamaStripe() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3, null); // gratuita
        String requestId = solicitarVaga(passenger.token(), rideId, "João Souza");
        aceitar(driver.token(), rideId, requestId, null);

        fakeGateway.reset();
        cancelar(passenger.token(), rideId, requestId, "Desisti");

        Assertions.assertTrue(fakeGateway.calls.isEmpty(),
                "carona gratuita não pode chamar o Stripe");
        Assertions.assertTrue(auditoriaDaReserva(requestId).isEmpty(),
                "sem estorno => sem registro de estorno na auditoria");
    }

    @Test
    @DisplayName("RN04: falha no Stripe NÃO impede o cancelamento e audita ESTORNO_FALHOU")
    void falhaNoStripeNaoImpedeCancelamento() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3, "25.00");
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");
        aceitar(driver.token(), rideId, requestId, "pi_IT_falha");

        fakeGateway.reset();
        fakeGateway.fail = true; // sandbox fora do ar / cartão inválido

        // o cancelamento continua retornando 200 e a vaga é liberada
        cancelar(passenger.token(), rideId, requestId, "Imprevisto");

        Assertions.assertEquals(
                List.of("pi_IT_falha:2500"), fakeGateway.calls,
                "a chamada foi tentada");

        List<Map<String, Object>> audit = auditoriaDaReserva(requestId);
        Assertions.assertEquals(1, audit.size());
        Assertions.assertEquals("ESTORNO_FALHOU", audit.get(0).get("action"));

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM ride_passengers WHERE id = CAST(? AS uuid)",
                String.class, requestId);
        Assertions.assertEquals("CANCELLED", status,
                "a reserva é cancelada mesmo com falha no estorno");
    }

    @Test
    @DisplayName("RN04: reserva paga SEM paymentIntentId não estorna (nada a reembolsar)")
    void pagaSemPaymentIntentNaoEstorna() {
        DriverSetup driver = criarMotorista();
        UserSetup passenger = criarPassageiro();
        String rideId = criarCarona(driver, 3, "30.00");
        String requestId = solicitarVaga(passenger.token(), rideId, "Maria Silva");
        aceitar(driver.token(), rideId, requestId, null); // sem paymentIntent

        fakeGateway.reset();
        cancelar(passenger.token(), rideId, requestId, "Imprevisto");

        Assertions.assertTrue(fakeGateway.calls.isEmpty());
        Assertions.assertTrue(auditoriaDaReserva(requestId).isEmpty());
    }
}
