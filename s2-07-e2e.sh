#!/usr/bin/env bash
# =============================================================================
# s2-07-e2e.sh — S2-07 (Issue #14): Testes E2E do fluxo de reservas
# =============================================================================
# Script ÚNICO e AUTOCONTIDO para a raiz do projeto. Ele implementa TUDO que a
# issue pede, em 4 passos:
#
#   1. IMPLEMENTA — instala o teste de ponta a ponta ReservaE2EIT.java
#      (fonte embutido abaixo, cobre os 5 cenários da issue:
#       CT-E2E-01 fluxo feliz: motorista publica -> passageiro solicita com
#       bagagem -> motorista aceita -> passageiro cancela -> vaga liberada;
#       CT-E2E-02 solicitação sem bagagem -> 400; CT-E2E-03 aceite em carona
#       lotada -> 409; CT-E2E-04 cancelamento após o início -> 409;
#       CT-E2E-05 estorno em carona gratuita NÃO chama o Stripe).
#      Idempotente: se o arquivo já existe e é idêntico, nada é alterado.
#   2. VALIDA — pré-requisitos: RaijiTestSupport (reuso exigido pela issue),
#       plugin maven-failsafe no raiji-app, JDK 21 + Maven 3.9+.
#   3. RODA — via `mvn verify` (failsafe), Testcontainers Postgres + Keycloak
#       (padrão do CI) ou modo --native sem Docker; esperas com Awaitility,
#       nenhum Thread.sleep; relatório em raiji-app/target/failsafe-reports.
#   4. AUDITA — lê o relatório do failsafe, imprime o resumo dos 5 cenários
#       e devolve exit code != 0 se qualquer teste falhar (pronto p/ CI).
#
# Uso (na raiz do projeto):
#   ./s2-07-e2e.sh                  # ciclo completo: instala + roda (Docker)
#   ./s2-07-e2e.sh --native         # sem Docker: sobe Postgres+Keycloak locais
#   ./s2-07-e2e.sh --skip-unit      # pula os testes unitários
#   ./s2-07-e2e.sh --full           # roda TODA a suíte de ITs do raiji-app
#   ./s2-07-e2e.sh --check          # só instala/valida; NÃO executa os testes
#   ./s2-07-e2e.sh --force          # reinstala o teste sobrescrevendo o atual
#
# CI: o relatório XML fica em raiji-app/target/failsafe-reports/
#     TEST-com.raiji.it.ReservaE2EIT.xml — arquive-o como artifact do job.
# =============================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IT_PATH="raiji-app/src/test/java/com/raiji/it/ReservaE2EIT.java"
REPORT_DIR="raiji-app/target/failsafe-reports"
TOOLS_DIR="${RAIJI_E2E_TOOLS:-$HOME/.raiji-e2e}"

FORCE=0; CHECK=0; NATIVE=0; FULL=0; SKIP_UNIT=0; IT_CLASS="ReservaE2EIT"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --native)    NATIVE=1; shift ;;
    --full)      FULL=1; shift ;;
    --skip-unit) SKIP_UNIT=1; shift ;;
    --check)     CHECK=1; shift ;;
    --force)     FORCE=1; shift ;;
    --it)        IT_CLASS="$2"; shift 2 ;;
    -h|--help)   sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Opção desconhecida: $1 (use --help)"; exit 2 ;;
  esac
done

log()  { printf '\033[1;34m[s2-07]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[s2-07] ✔\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[s2-07] ERRO:\033[0m %s\n' "$*" >&2; exit 1; }

cd "$REPO_DIR"

# -----------------------------------------------------------------------------
# PASSO 1 — IMPLEMENTA: instala a ReservaE2EIT a partir do fonte embutido
# -----------------------------------------------------------------------------
install_reserva_e2e_it() {
  local destino="$REPO_DIR/$IT_PATH" tmp
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' RETURN
  cat > "$tmp" <<'RAIJI_E2E_IT_EOF'
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

        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("role", role, "userType", userType))
                .when().post(app("/api/users/me/profile"))
                .then().statusCode(200);

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
    @DisplayName("CT-E2E-02: solicitação SEM bagagem é rejeitada com 400 e nada é persistido")
    void solicitacaoSemBagagemRetorna400() {
        Motorista motorista = cadastrarMotorista();
        Usuario passageiro = cadastrarPassageiro();
        String rideId = publicarCarona(motorista, 3, "50.00");

        Map<String, Object> semBagagem = new HashMap<>();
        semBagagem.put("passengerName", "Maria Sem Mala");

        given().auth().oauth2(passageiro.token())
                .contentType(ContentType.JSON).body(semBagagem)
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(400);

        // também com array vazio
        given().auth().oauth2(passageiro.token())
                .contentType(ContentType.JSON)
                .body(Map.of("passengerName", "Maria Sem Mala", "baggage", List.of()))
                .when().post(app("/api/rides/" + rideId + "/requests"))
                .then().statusCode(400);

        assertTrue(solicitacoesDoMotorista(motorista.token(), rideId).isEmpty(),
                "nenhuma solicitação persistida sem bagagem válida");
        assertEquals(3, vagasDisponiveis(rideId), "vagas intactas");
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
RAIJI_E2E_IT_EOF

  if [[ -f "$destino" ]] && cmp -s "$tmp" "$destino"; then
    ok "ReservaE2EIT.java já instalado e idêntico ao fonte da issue (nada a fazer)"
  elif [[ -f "$destino" ]] && [[ $FORCE -eq 0 ]]; then
    log "ReservaE2EIT.java existente difere do embutido — preservado (use --force para sobrescrever)"
  else
    mkdir -p "$(dirname "$destino")"
    cp "$tmp" "$destino"
    ok "ReservaE2EIT.java instalado em $IT_PATH (${IT_CLASS} com os 5 cenários)"
  fi
}

install_reserva_e2e_it

# -----------------------------------------------------------------------------
# PASSO 2 — VALIDA: pré-requisitos da issue
# -----------------------------------------------------------------------------
[[ -f raiji-app/pom.xml ]] || fail "raiji-app/pom.xml não encontrado — rode na RAIZ do projeto AVXAE-CORE-MONOLITH."
[[ -f raiji-app/src/test/java/com/raiji/it/RaijiTestSupport.java ]] \
  || fail "RaijiTestSupport não encontrado (a issue exige reusar a base existente)."
grep -q "maven-failsafe-plugin" raiji-app/pom.xml \
  || fail "maven-failsafe-plugin ausente em raiji-app/pom.xml — ITs *IT não serão executadas."
command -v java  >/dev/null || fail "java não encontrado (JDK 21 exigido)."
command -v javac >/dev/null || fail "javac não encontrado — instale um JDK completo (não apenas o JRE)."
command -v mvn   >/dev/null || fail "mvn não encontrado no PATH (Maven 3.9+)."
ok "Pré-requisitos OK — Java: $(java -version 2>&1 | head -1) | Maven: $(mvn -version 2>/dev/null | sed -n 's/.*Apache Maven //p' | head -1)"

[[ $CHECK -eq 1 ]] && { ok "modo --check: implementação e pré-requisitos validados (testes NÃO executados)"; exit 0; }

# -----------------------------------------------------------------------------
# PASSO 3 — RODA: failsafe com Testcontainers (padrão) ou modo nativo
# -----------------------------------------------------------------------------
if [[ $NATIVE -eq 1 ]]; then
  log "Modo NATIVO (sem Docker): Postgres 17 + Keycloak 26 locais"
  PG_BIN="$TOOLS_DIR/zonky17/bin"; PG_DATA="$TOOLS_DIR/pgdata"; KC_DIR="$TOOLS_DIR/keycloak-26.0.5"

  probe_pg() { (echo > /dev/tcp/127.0.0.1/5432) >/dev/null 2>&1; }
  if probe_pg; then
    log "Postgres já ativo em 127.0.0.1:5432"
  else
    log "Subindo Postgres 17 local..."
    mkdir -p "$TOOLS_DIR"
    if [[ ! -x "$PG_BIN/initdb" ]]; then
      log "  baixando binários do Postgres (zonky, sem root)..."
      curl -sL -o "$TOOLS_DIR/zonky17.jar" \
        "https://repo.maven.apache.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/17.2.0/embedded-postgres-binaries-linux-amd64-17.2.0.jar"
      mkdir -p "$TOOLS_DIR/zonky17" && (cd "$TOOLS_DIR/zonky17" \
        && python3 -c "import zipfile;zipfile.ZipFile('$TOOLS_DIR/zonky17.jar').extractall('.')" \
        && tar xJf postgres-linux-x86_64.txz)
    fi
    [[ -d "$PG_DATA" ]] || "$PG_BIN/initdb" -D "$PG_DATA" -U raiji -A trust --locale=C --encoding=UTF8 > /dev/null
    "$PG_BIN/pg_ctl" -D "$PG_DATA" -l "$TOOLS_DIR/pg.log" \
      -o "-p 5432 -c listen_addresses=127.0.0.1" start
    for _ in $(seq 1 20); do probe_pg && break; sleep 1; done
    probe_pg || fail "Postgres não subiu (veja $TOOLS_DIR/pg.log)"
    echo "CREATE DATABASE raiji_app;" | "$PG_BIN/postgres" --single -D "$PG_DATA" postgres >/dev/null 2>&1 || true
  fi
  export RAIJI_IT_NATIVE=true
  export RAIJI_IT_DB_URL="${RAIJI_IT_DB_URL:-jdbc:postgresql://localhost:5432/raiji_app}"
  export RAIJI_IT_DB_USER="${RAIJI_IT_DB_USER:-raiji}"
  export RAIJI_IT_DB_PASSWORD="${RAIJI_IT_DB_PASSWORD:-raiji123}"

  probe_kc() { curl -s -o /dev/null -w "%{http_code}" "http://localhost:8180/realms/raiji" 2>/dev/null | grep -q 200; }
  if probe_kc; then
    log "Keycloak já ativo em http://localhost:8180 (realm raiji ok)"
  else
    log "Subindo Keycloak 26.0.5 local (start-dev + import do realm)..."
    mkdir -p "$TOOLS_DIR"
    if [[ ! -x "$KC_DIR/bin/kc.sh" ]]; then
      log "  baixando keycloak-26.0.5.tar.gz (~150 MB)..."
      curl -sL -o "$TOOLS_DIR/keycloak.tar.gz" \
        "https://github.com/keycloak/keycloak/releases/download/26.0.5/keycloak-26.0.5.tar.gz"
      (cd "$TOOLS_DIR" && tar xzf keycloak.tar.gz)
    fi
    mkdir -p "$KC_DIR/data/import"
    cp raiji-app/src/test/resources/raiji-realm.json "$KC_DIR/data/import/"
    KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
      nohup "$KC_DIR/bin/kc.sh" start-dev --http-port=8180 --import-realm \
      > "$TOOLS_DIR/keycloak.log" 2>&1 &
    log "  aguardando o realm raiji responder..."
    for _ in $(seq 1 90); do probe_kc && break; sleep 2; done
    probe_kc || fail "Keycloak não ficou saudável (veja $TOOLS_DIR/keycloak.log)"
  fi
  export RAIJI_IT_KEYCLOAK_URL="${RAIJI_IT_KEYCLOAK_URL:-http://localhost:8180}"
else
  command -v docker >/dev/null \
    || fail "Docker não encontrado — rode com --native (infra sem Docker) ou instale o Docker."
  log "Modo Testcontainers (padrão do CI): Postgres 17 + Keycloak 26 via Docker"
fi

MAVEN_ARGS=(-B -ntp)
[[ $SKIP_UNIT -eq 1 ]] && MAVEN_ARGS+=(-DskipTests)

if [[ $FULL -eq 1 ]]; then
  log "Rodando a suíte COMPLETA (todos os módulos + todas as ITs)..."
  mvn "${MAVEN_ARGS[@]}" verify
else
  # Executa SOMENTE a IT da issue. A cobertura (JaCoCo >= 80%) é medida sobre a
  # suíte inteira no CI; aqui relaxamos o gate APENAS nesta execução parcial.
  log "Rodando $IT_CLASS via failsafe (unitários incluídos)..."
  mvn "${MAVEN_ARGS[@]}" -pl raiji-app -am verify \
    -Dit.test="$IT_CLASS" -Dcoverage.minimum=0.0
fi

# -----------------------------------------------------------------------------
# PASSO 4 — AUDITA: resumo do failsafe + exit code para o CI
# -----------------------------------------------------------------------------
REPORT_TXT="$REPORT_DIR/com.raiji.it.$IT_CLASS.txt"
[[ -f "$REPORT_TXT" ]] || fail "relatório $REPORT_TXT não foi gerado."

RESUMO="$(grep -m1 'Tests run:' "$REPORT_TXT" || true)"
log "Resumo do failsafe: $RESUMO"
RUNS=$(sed -n 's/.*Tests run: \([0-9]*\),.*/\1/p' <<<"$RESUMO")
FAILS=$(sed -n 's/.*, Failures: \([0-9]*\),.*/\1/p' <<<"$RESUMO")
ERRS=$(sed -n 's/.*, Errors: \([0-9]*\),.*/\1/p' <<<"$RESUMO")

if [[ "$FAILS" -eq 0 && "$ERRS" -eq 0 && "$RUNS" -ge 5 ]]; then
  ok "$RUNS cenários E2E passaram (fluxo feliz + 400/409/409 + estorno sem Stripe)"
  ok "Relatórios (archive como artifact no CI):"
  ls -1 "$REPORT_DIR"/*.txt "$REPORT_DIR"/*.xml 2>/dev/null | sed 's/^/         /'
  exit 0
fi
fail "$FAILS falha(s) e $ERRS erro(s) em $RUNS cenários — veja $REPORT_DIR"
