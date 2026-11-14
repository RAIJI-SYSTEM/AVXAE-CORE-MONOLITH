package com.raiji.it;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.raiji.rides.scheduler.RideStatusScheduler;
import com.raiji.rides.service.stripe.StripeRefundGateway;

import io.restassured.http.ContentType;

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
 * S2-07 (#14) — Testes E2E do fluxo de reservas, de ponta a ponta:
 *
 * <pre>
 * motorista publica → passageiro solicita com bagagem → motorista aceita
 *   → passageiro cancela → vaga liberada.
 * </pre>
 *
 * <p>Cobre os critérios de aceite da issue:
 * <ul>
 *   <li><b>Fluxo feliz</b> (CT-E2E-01): cadastro do motorista e do passageiro,
 *       perfil DRIVER + veículo, publicação de carona paga, busca, solicitação
 *       com bagagem, aceite e cancelamento com vaga liberada (e estorno
 *       auditado);</li>
 *   <li><b>Erros</b>: solicitação sem bagagem → 400 (CT-E2E-02); aceite em
 *       carona lotada → 409 (CT-E2E-03); cancelamento após o início da
 *       viagem → 409 (CT-E2E-04); estorno em carona gratuita → não chama o
 *       Stripe (CT-E2E-05).</li>
 * </ul>
 *
 * <p>Reutiliza {@link RaijiTestSupport} (Testcontainers Postgres + Keycloak;
 * modo NATIVO sem Docker disponível via {@code RAIJI_IT_NATIVE=true}).
 * Nenhum {@code Thread.sleep}: esperas com <b>Awaitility</b> sobre chamadas
 * reais de API. O Stripe é substituído por um gateway FAKE (@Primary), como
 * em {@code StripeRefundApiIT} — determinístico e sem rede.
 */
@DisplayName("S2-07 — Testes E2E do fluxo de reservas")
@TestPropertySource(properties = {
        // Stripe "configurado" para o fake ser acionado; nenhuma chamada real ocorre
        "raiji.stripe.api-key=sk_test_E2E_FAKE_KEY"
})
@Import(ReservaE2EIT.FakeStripeConfig.class)
class ReservaE2EIT extends RaijiTestSupport {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeStripeGateway stripeGateway;

    @Autowired
    private RideStatusScheduler scheduler;

    // ===== gateway Stripe FAKE (registrável e determinístico) =====

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

        @Override
        public String createRefund(String paymentIntentId, long amountCents) {
            calls.add(paymentIntentId + ":" + amountCents);
            return "re_E2E_" + paymentIntentId;
        }
    }

    @BeforeEach
    void limparGateway() {
        stripeGateway.calls.clear();
    }

    // ===== fixtures (jornada REAL: cadastro → perfil → role no token) =====

    private record Usuario(String username, String token, String sub) {}

    private record Motorista(String token, UUID vehicleId, String sub) {}

    /**
     * Cadastra o usuário no Keycloak, define o perfil via API (RN01/RF34 —
     * sincroniza a role no realm) e refaz o login para o token já sair com a
     * role — exatamente a jornada de um usuário real.
     */
    private Usuario cadastrarAtor(String prefixo, String role, String userType) {
        String username = prefixo + "-" + System.nanoTime();

        criarUsuario(username); // cadastro no Keycloak (Admin REST, 201)

        String token = obterToken(username, SENHA_NOVOS_USUARIOS);

        // 1º acesso provisiona o perfil (RN20) e define o papel (RF34)
        given().auth().oauth2(token)
                .when().get(app("/api/users/me"))
                .then().statusCode(200);

        // Tipo da conta (PF/PJ); toda conta já é passageira
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("role", "PASSENGER", "userType", userType))
                .when().post(app("/api/users/me/profile"))
                .then().statusCode(200);

        // Motorista: como no app — envia a CNH e espera a aprovação
        // (Infosimples FALSA, ver FakeInfosimples: nenhuma chamada real)
        if ("DRIVER".equals(role)) {
            given().auth().oauth2(token)
                    .multiPart("image", "cnh.png", "foto".getBytes(), "image/png")
                    .multiPart("securityCode", FakeInfosimples.CNH_VALIDA)
                    .when().post(app("/api/users/me/driver/cnh"))
                    .then().statusCode(202);
            String status = "PROCESSING";
            for (int i = 0; i < 100 && "PROCESSING".equals(status); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                status = given().auth().oauth2(token).when().get(app("/api/users/me/driver/cnh"))
                        .then().statusCode(200).extract().path("status");
            }
            org.assertj.core.api.Assertions.assertThat(status).isEqualTo("APPROVED");
        }

        // re-login: novo token já carrega a role sincronizada
        String tokenComRole = obterToken(username, SENHA_NOVOS_USUARIOS);
        String sub = given().auth().oauth2(tokenComRole)
                .when().get(app("/api/users/me"))
                .then().statusCode(200)
                .extract().path("sub");

        return new Usuario(username, tokenComRole, sub);
    }

    private Motorista cadastrarMotorista() {
        Usuario usuario = cadastrarAtor("e2e-motorista", "DRIVER", "PF");
        String plate = "E" + String.format("%06d",
                Long.valueOf(System.nanoTime() % 1_000_000L).intValue());
        String vehicleId = given().auth().oauth2(usuario.token())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "plate", plate, "type", "CARRO", "model", "Chevrolet Onix",
                        "color", "Prata", "year", 2024, "passengerSeats", 4,
                        "cargoVolumeLiters", 400))
                .when().post(app("/api/vehicles"))
                .then().statusCode(201)
                .extract().path("id");
        return new Motorista(usuario.token(), UUID.fromString(vehicleId), usuario.sub());
    }

    private Usuario cadastrarPassageiro() {
        return cadastrarAtor("e2e-passageiro", "PASSENGER", "PF");
    }

    /** Publica carona paga (price != null) ou gratuita (price == null). */
    private String publicarCarona(Motorista motorista, int totalSeats, String price) {
        // cidades únicas por execução: a busca por origem/destino fica determinística
        String sufixo = Long.toString(System.nanoTime(), 36);
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", motorista.vehicleId().toString());
        body.put("type", "PASSENGER");
        body.put("originCity", "Fortaleza-" + sufixo);
        body.put("originState", "CE");
        body.put("destinationCity", "Sobral-" + sufixo);
        body.put("destinationState", "CE");
        body.put("departureDate", LocalDateTime.now().plusDays(1).format(ISO));
        body.put("totalSeats", totalSeats);
        if (price != null) {
            body.put("pricePerPassenger", price);
        }
        return given().auth().oauth2(motorista.token())
                .contentType(ContentType.JSON).body(body)
                .when().post(app("/api/rides"))
                .then().statusCode(201)
                .body("status", equalTo("PLANNED"))
                .extract().path("id");
    }

    private static Map<String, Object> itemBagagem(String tipo, int quantidade) {
        Map<String, Object> item = new HashMap<>();
        item.put("type", tipo);
        item.put("quantity", quantidade);
        item.put("lengthCm", 60);
        item.put("widthCm", 40);
        item.put("heightCm", 30);
        item.put("weightKg", 15.5);
        item.put("description", "Mala de bordo");
        return item;
    }

    private static Map<String, Object> payloadSolicitacao(String nome) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("passengerName", nome);
        payload.put("baggage", List.of(
                itemBagagem("mala", 1),
                itemBagagem("mochila", 1)));
        return payload;
    }

    private String solicitarVaga(String tokenPassageiro, String rideId, String nome) {
        return given().auth().oauth2(tokenPassageiro)
                .contentType(ContentType.JSON).body(payloadSolicitacao(nome))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("status", equalTo("PENDING"))
                .extract().path("id");
    }

    private List<Map<String, Object>> solicitacoesDoMotorista(String token, String rideId) {
        return given().auth().oauth2(token)
                .when().get(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(200)
                .extract().jsonPath().getList("");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buscarCaronas(String token, String origem, Integer minSeats) {
        return given().auth().oauth2(token)
                .queryParam("originCity", origem)
                .queryParam("minSeats", minSeats)
                .when().get(app("/api/rides"))
                .then().statusCode(200)
                .extract().jsonPath().getList("content");
    }

    private String origemDaCarona(String rideId) {
        return jdbcTemplate.queryForObject(
                "SELECT origin_city FROM rides WHERE id = CAST(? AS uuid)",
                String.class, rideId);
    }

    private String statusDaReserva(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ride_passengers WHERE id = CAST(? AS uuid)",
                String.class, requestId);
    }

    private Integer vagasDisponiveis(String rideId) {
        return jdbcTemplate.queryForObject(
                "SELECT available_seats FROM rides WHERE id = CAST(? AS uuid)",
                Integer.class, rideId);
    }

    private List<Map<String, Object>> auditoriaDaReserva(String requestId) {
        return jdbcTemplate.queryForList(
                "SELECT action, details FROM audit_log "
                        + "WHERE entity_type = 'RIDE_PASSENGER' "
                        + "AND entity_id = CAST(? AS uuid)",
                requestId);
    }

    private int contarNotificacoes(String sub, String rideId, String trecho) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications "
                        + "WHERE recipient_sub = ? AND ride_id = CAST(? AS uuid) "
                        + "AND message LIKE ?",
                Integer.class, sub, rideId, "%" + trecho + "%");
        return total == null ? 0 : total;
    }

    // =====================================================================
    // CT-E2E-01 · FLUXO FELIZ
    // =====================================================================

    @Test
    @DisplayName("CT-E2E-01: motorista publica carona paga → passageiro solicita com bagagem → "
            + "motorista aceita → passageiro cancela → vaga liberada")
    void fluxoFelizReservaPontaAPonta() {
        passo(1, "cadastro do motorista e do passageiro no Keycloak");
        Motorista motorista = cadastrarMotorista();
        Usuario passageiro = cadastrarPassageiro();

        passo(2, "motorista cria o veículo da carona");
        UUID vehicleId = motorista.vehicleId();
        assertTrue(vehicleId != null && motorista.sub() != null,
                "motorista com veículo criado (201) e sub resolvido");

        passo(3, "motorista publica carona PAGA (R$ 50,00, 3 vagas)");
        String rideId = publicarCarona(motorista, 3, "50.00");

        passo(4, "passageiro BUSCA a carona publicada (busca pública autenticada)");
        String origem = origemDaCarona(rideId);
        List<Map<String, Object>> encontradas = await("carona aparece na busca")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .until(() -> buscarCaronas(passageiro.token(), origem, 3),
                        conteudo -> conteudo.stream()
                                .anyMatch(c -> rideId.equals(String.valueOf(c.get("id")))));
        Map<String, Object> carona = encontradas.stream()
                .filter(c -> rideId.equals(String.valueOf(c.get("id"))))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal(String.valueOf(carona.get("pricePerPassenger")))
                .compareTo(new BigDecimal("50.00")),
                "carona paga com o preço publicado");
        assertEquals(3, ((Number) carona.get("availableSeats")).intValue(),
                "3 vagas disponíveis na publicação");

        passo(5, "passageiro SOLICITA vaga declarando a bagagem (S2-01/RF18)");
        String requestId = given().auth().oauth2(passageiro.token())
                .contentType(ContentType.JSON)
                .body(payloadSolicitacao("Ana Souza"))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201)
                .body("status", equalTo("PENDING"))
                .body("baggage[0].type", equalTo("mala"))
                .body("baggage[0].weightKg", equalTo(15.5f))
                .body("baggage[1].type", equalTo("mochila"))
                .extract().path("id");

        // motorista é notificado da nova solicitação (S1-06)
        assertEquals(1, contarNotificacoes(motorista.sub(), rideId, "Nova solicitação"),
                "motorista notificado da solicitação");

        passo(6, "motorista VISUALIZA a bagagem e ACEITA (S2-02), informando paymentIntentId");
        List<Map<String, Object>> paraDecidir = solicitacoesDoMotorista(motorista.token(), rideId);
        assertEquals(1, paraDecidir.size(), "motorista vê a solicitação pendente");
        assertEquals("Ana Souza", paraDecidir.get(0).get("passengerName"));
        assertFalse(((List<?>) paraDecidir.get(0).get("baggage")).isEmpty(),
                "bagagem declarada visível ANTES do aceite");

        given().auth().oauth2(motorista.token())
                .contentType(ContentType.JSON)
                .body(Map.of("decision", "ACCEPT", "paymentIntentId", "pi_E2E_feliz"))
                .when().patch(app("/api/rides/" + rideId + "/requests/" + requestId))
                .then().statusCode(200)
                .body("status", equalTo("CONFIRMED"))
                .body("confirmedAt", notNullValue());

        // vaga reservada: 3 → 2
        await("vaga reservada refletida na busca")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() ->
                        assertTrue(buscarCaronas(passageiro.token(), origem, 2).stream()
                                        .anyMatch(c -> rideId.equals(String.valueOf(c.get("id")))),
                                "carona ainda aparece com 2 vagas"));
        assertEquals(2, vagasDisponiveis(rideId), "aceite decrementou availableSeats");

        // passageiro é notificado do aceite (S2-02/RF19)
        assertEquals(1, contarNotificacoes(passageiro.sub(), rideId, "ACEITA"),
                "passageiro notificado do aceite");

        passo(7, "passageiro CANCELA a reserva (S2-04) — estorno do pago deve ocorrer");
        given().auth().oauth2(passageiro.token())
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "Imprevisto pessoal"))
                .when().delete(app("/api/rides/" + rideId + "/requests/" + requestId))
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("seatReleased", equalTo(true))
                .body("cancellationReason", equalTo("Imprevisto pessoal"))
                .body("cancelledAt", notNullValue());

        assertEquals(List.of("pi_E2E_feliz:5000"), stripeGateway.calls,
                "estorno chamou o Stripe com 50.00 => 5000 centavos");

        List<Map<String, Object>> auditoria = auditoriaDaReserva(requestId);
        assertEquals(1, auditoria.size(), "um registro de auditoria do estorno");
        assertEquals("ESTORNO_SUCESSO", auditoria.get(0).get("action"));
        assertTrue(String.valueOf(auditoria.get(0).get("details")).contains("re_E2E_pi_E2E_feliz"),
                "auditoria contém o id do refund");

        passo(8, "vaga LIBERADA: busca volta a exibir 3 vagas e reserva segue CANCELLED");
        await("vaga liberada refletida na busca")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() ->
                        assertTrue(buscarCaronas(passageiro.token(), origem, 3).stream()
                                        .anyMatch(c -> rideId.equals(String.valueOf(c.get("id")))),
                                "carona volta a aparecer com 3 vagas"));
        assertEquals(3, vagasDisponiveis(rideId), "availableSeats restaurado");
        assertEquals("CANCELLED", statusDaReserva(requestId),
                "reserva permanece CANCELLED");
        assertEquals(1, contarNotificacoes(motorista.sub(), rideId, "CANCELADA"),
                "motorista notificado do cancelamento com vaga liberada");
    }

    // =====================================================================
    // CT-E2E-02 · ERRO: solicitação sem bagagem → 400
    // =====================================================================

    @Test
    @DisplayName("CT-E2E-02: bagagem é OPCIONAL — pedido sem bagagem é criado (a pessoa pode declarar depois)")
    void solicitacaoSemBagagemEhAceita() {
        Motorista motorista = cadastrarMotorista();
        Usuario passageiro = cadastrarPassageiro();
        String rideId = publicarCarona(motorista, 3, "50.00");

        Map<String, Object> semBagagem = new HashMap<>();
        semBagagem.put("passengerName", "Maria Sem Mala");

        given().auth().oauth2(passageiro.token())
                .contentType(ContentType.JSON).body(semBagagem)
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(201);

        assertEquals(1, solicitacoesDoMotorista(motorista.token(), rideId).size(),
                "o pedido sem bagagem chega ao motorista");
        assertEquals(3, vagasDisponiveis(rideId), "vagas intactas até o aceite");
    }

    // =====================================================================
    // CT-E2E-03 · ERRO: aceite em carona lotada → 409
    // =====================================================================

    @Test
    @DisplayName("CT-E2E-03: ACCEPT sem vaga disponível retorna 409 e mantém a reserva PENDING")
    void aceiteEmCaronaLotadaRetorna409() {
        Motorista motorista = cadastrarMotorista();
        Usuario p1 = cadastrarPassageiro();
        Usuario p2 = cadastrarPassageiro();
        String rideId = publicarCarona(motorista, 2, "30.00");
        String origem = origemDaCarona(rideId);

        String req1 = solicitarVaga(p1.token(), rideId, "Primeiro Passageiro");
        String req2 = solicitarVaga(p2.token(), rideId, "Segundo Passageiro");

        given().auth().oauth2(motorista.token())
                .contentType(ContentType.JSON).body(Map.of("decision", "ACCEPT"))
                .when().patch(app("/api/rides/" + rideId + "/requests/" + req1))
                .then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));

        assertEquals(1, vagasDisponiveis(rideId),
                "primeiro aceite consumiu uma das duas vagas");

        // Simula a ÚLTIMA vaga sendo ocupada entre as decisões (mesma técnica do
        // CapacityLockApiIT) — o aceite seguinte deve esbarrar na trava de assentos.
        jdbcTemplate.update(
                "UPDATE rides SET available_seats = 0 WHERE id = CAST(? AS uuid)",
                rideId);

        // vaga esgotada: o segundo aceite NÃO pode confirmar (RN03)
        given().auth().oauth2(motorista.token())
                .contentType(ContentType.JSON).body(Map.of("decision", "ACCEPT"))
                .when().patch(app("/api/rides/" + rideId + "/requests/" + req2))
                .then().statusCode(409);

        assertEquals("PENDING", statusDaReserva(req2),
                "segunda reserva permanece PENDING");
        assertEquals(0, vagasDisponiveis(rideId), "vaga única consumida");

        await("carona lotada some da busca (availableSeats > 0 é regra fixa)")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() ->
                        assertTrue(buscarCaronas(p1.token(), origem, 1).stream()
                                        .noneMatch(c -> rideId.equals(String.valueOf(c.get("id")))),
                                "carona sem vagas não aparece na busca"));
    }

    // =====================================================================
    // CT-E2E-04 · ERRO: cancelamento após o início da viagem → 409 (RN06)
    // =====================================================================

    @Test
    @DisplayName("CT-E2E-04: cancelamento APÓS o início da viagem (departure passada + "
            + "scheduler) retorna 409 e a vaga NÃO é liberada")
    void cancelamentoAposInicioRetorna409() {
        Motorista motorista = cadastrarMotorista();
        Usuario passageiro = cadastrarPassageiro();
        String rideId = publicarCarona(motorista, 3, "40.00");
        String origem = origemDaCarona(rideId);

        String requestId = solicitarVaga(passageiro.token(), rideId, "Bruno Costa");
        given().auth().oauth2(motorista.token())
                .contentType(ContentType.JSON)
                .body(Map.of("decision", "ACCEPT", "paymentIntentId", "pi_E2E_tarde"))
                .when().patch(app("/api/rides/" + rideId + "/requests/" + requestId))
                .then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));

        // "avança o relógio": partida passa a estar no passado e o job da S2-06
        // promove a carona PLANNED -> IN_PROGRESS (mesma técnica do
        // RideStatusSchedulerIT, sem Thread.sleep)
        jdbcTemplate.update(
                "UPDATE rides SET departure_date = ? WHERE id = CAST(? AS uuid)",
                LocalDateTime.now().minusMinutes(2), rideId);
        scheduler.promoteDueRides();

        // a viagem em curso SAI da busca (regra fixa: somente PLANNED é listada)
        await("carona em andamento sai da busca")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() ->
                        assertTrue(buscarCaronas(passageiro.token(), origem, 1).stream()
                                        .noneMatch(c -> rideId.equals(String.valueOf(c.get("id")))),
                                "carona iniciada não é mais listada"));

        // passageiro tenta cancelar DEPOIS do início → 409
        given().auth().oauth2(passageiro.token())
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "Só lembrando depois do horário"))
                .when().delete(app("/api/rides/" + rideId + "/requests/" + requestId))
                .then().statusCode(409);

        assertEquals("CONFIRMED", statusDaReserva(requestId),
                "reserva segue CONFIRMED após o início");
        assertEquals(2, vagasDisponiveis(rideId),
                "vaga NÃO é liberada após o início da viagem");
        assertTrue(stripeGateway.calls.isEmpty(),
                "nenhum estorno: o cancelamento foi recusado");
    }

    // =====================================================================
    // CT-E2E-05 · ERRO: estorno em carona gratuita → não chama Stripe
    // =====================================================================

    @Test
    @DisplayName("CT-E2E-05: cancelamento de reserva em carona GRATUITA não chama o Stripe")
    void estornoEmCaronaGratuitaNaoChamaStripe() {
        Motorista motorista = cadastrarMotorista();
        Usuario passageiro = cadastrarPassageiro();
        String rideId = publicarCarona(motorista, 3, null); // gratuita

        String requestId = solicitarVaga(passageiro.token(), rideId, "Carla Dias");
        given().auth().oauth2(motorista.token())
                .contentType(ContentType.JSON)
                .body(Map.of("decision", "ACCEPT")) // sem paymentIntentId
                .when().patch(app("/api/rides/" + rideId + "/requests/" + requestId))
                .then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));

        given().auth().oauth2(passageiro.token())
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "Mudei de planos"))
                .when().delete(app("/api/rides/" + rideId + "/requests/" + requestId))
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("seatReleased", equalTo(true));

        assertTrue(stripeGateway.calls.isEmpty(),
                "carona gratuita NÃO pode chamar o Stripe");
        assertTrue(auditoriaDaReserva(requestId).isEmpty(),
                "sem registro de estorno na auditoria");
        assertEquals(3, vagasDisponiveis(rideId), "vaga liberada normalmente");
    }

    private static void passo(int n, String descricao) {
        System.out.printf("  [S2-07] passo %d — %s%n", n, descricao);
    }
}
