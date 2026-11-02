#!/usr/bin/env python3
"""Verificação estrutural da S2-02 (RF08/RF19) — aceite/recusa com bagagem.

Roda SEM Maven/Docker: valida que o gerador criou/patcheou todos os
arquivos exigidos pela issue #9 e que os critérios de aceite estão
cobertos por testes. Para a validação real (compilação + testes + IT):

    bash tests/s2-02/run-maven.sh
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RIDES_MAIN = ROOT / "raiji-rides/src/main/java/com/raiji/rides"
RIDES_TEST = ROOT / "raiji-rides/src/test/java/com/raiji/rides"
APP_IT = ROOT / "raiji-app/src/test/java/com/raiji/it"
MIG = ROOT / "raiji-rides/src/main/resources/db/migration"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


class TestNovosArquivos(unittest.TestCase):
    def test_arquivos_devem_existir(self):
        esperados = [
            RIDES_MAIN / "domain/RideDecision.java",
            RIDES_MAIN / "dto/RideDecisionRequest.java",
            RIDES_MAIN / "dto/RideDecisionResponse.java",
            RIDES_MAIN / "event/RideDecisionEvent.java",
            RIDES_MAIN / "web/RidePassengerController.java",
            APP_IT / "RideDecisionApiIT.java",
            ROOT / "scripts/setup-s2-02.sh",
            ROOT / "tests/s2-02/run-all.sh",
            ROOT / "tests/s2-02/run-maven.sh",
            ROOT / "tests/s2-02/test_s2_02.py",
        ]
        for p in esperados:
            self.assertTrue(p.is_file(), f"ausente: {p}")


class TestRideDecision(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "domain/RideDecision.java")

    def test_enum_accept_reject(self):
        self.assertIn("public enum RideDecision", self.src)
        self.assertRegex(self.src, r"\bACCEPT\b")
        self.assertRegex(self.src, r"\bREJECT\b")

    def test_json_creator_case_insensitive(self):
        self.assertIn("@JsonCreator", self.src)
        self.assertIn("equalsIgnoreCase", self.src)
        self.assertIn("IllegalArgumentException", self.src)

    def test_serializa_em_maiusculas(self):
        # sem @JsonValue: name() (ACCEPT/REJECT) é o formato do contrato
        self.assertNotIn("@JsonValue", self.src)


class TestRideDecisionRequest(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "dto/RideDecisionRequest.java")

    def test_record_com_validacoes(self):
        self.assertIn("public record RideDecisionRequest", self.src)
        self.assertIn("@NotNull", self.src)
        self.assertIn("RideDecision decision", self.src)
        self.assertIn("@Size(max = 500", self.src)
        self.assertIn("String reason", self.src)

    def test_reason_obrigatorio_no_reject(self):
        # mesmo padrão do UpdateProfileRequest.hasAtLeastOneField()
        self.assertIn("@AssertTrue", self.src)
        self.assertIn("hasReasonWhenReject", self.src)
        self.assertIn("RideDecision.REJECT", self.src)
        self.assertIn("isBlank", self.src)


class TestRideDecisionResponse(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "dto/RideDecisionResponse.java")

    def test_contrato_id_status_confirmedat(self):
        self.assertIn("public record RideDecisionResponse", self.src)
        for campo in ("UUID id", "RidePassengerStatus status",
                      "Instant confirmedAt"):
            self.assertIn(campo, self.src)
        self.assertIn("public static RideDecisionResponse from(RidePassenger",
                      self.src)


class TestRideDecisionEvent(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "event/RideDecisionEvent.java")

    def test_evento_com_componentes(self):
        self.assertIn("public record RideDecisionEvent", self.src)
        for comp in ("UUID rideId", "UUID requestId", "String driverSub",
                     "String driverName", "String passengerSub",
                     "String passengerName", "RideDecision decision",
                     "String reason", "Instant decidedAt"):
            self.assertIn(comp, self.src)


class TestRidePassengerController(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "web/RidePassengerController.java")

    def test_rota_patch(self):
        self.assertIn("@RestController", self.src)
        self.assertIn('@RequestMapping("/api/rides/{rideId}/requests")',
                      self.src)
        self.assertIn('@PatchMapping("/{requestId}")', self.src)

    def test_seguranca_dono_via_servico(self):
        self.assertIn("@PreAuthorize(\"hasRole('DRIVER')\")", self.src)
        self.assertIn("@AuthenticationPrincipal Jwt jwt", self.src)
        self.assertIn("jwt.getSubject()", self.src)

    def test_validacao_e_delegacao(self):
        self.assertIn("@Valid @RequestBody RideDecisionRequest request",
                      self.src)
        self.assertIn("ridePassengerService.decide(", self.src)
        self.assertIn("request.decision()", self.src)
        self.assertIn("request.reason()", self.src)

    def test_sem_colisao_com_ride_request_controller(self):
        rrc = read(RIDES_MAIN / "web/RideRequestController.java")
        self.assertNotIn("@PatchMapping", rrc,
                         "PATCH deve existir só no RidePassengerController")


class TestServiceDecide(unittest.TestCase):
    def setUp(self):
        self.src = read(
            RIDES_MAIN / "service/RidePassengerService.java")

    def test_metodo_decide_transacional(self):
        self.assertIn("public RideDecisionResponse decide(", self.src)
        idx = self.src.index("public RideDecisionResponse decide(")
        antes = self.src[max(0, idx - 200):idx]
        self.assertIn("@Transactional", antes)

    def test_guarda_400(self):
        self.assertIn("if (decision == null)", self.src)
        self.assertIn("decision == RideDecision.REJECT", self.src)
        self.assertIn("reason == null || reason.isBlank()", self.src)

    def test_guarda_404_com_filtro_de_carona(self):
        self.assertIn("ridePassengerRepository.findById(requestId)", self.src)
        self.assertIn("rideId.equals(p.getRideId())", self.src)
        self.assertIn("Solicitação não encontrada.", self.src)

    def test_guarda_403_dono(self):
        self.assertIn("!ride.getDriverSub().equals(driverSub)", self.src)
        self.assertIn("decidir solicitações desta carona", self.src)

    def test_guarda_409(self):
        self.assertIn("passenger.getStatus() != RidePassengerStatus.PENDING",
                      self.src)
        self.assertIn("Esta solicitação já foi decidida.", self.src)
        self.assertIn("Não há vagas disponíveis nesta carona.", self.src)

    def test_accept_decrementa_e_confirma(self):
        self.assertIn("ride.setAvailableSeats(ride.getAvailableSeats() - 1)",
                      self.src)
        self.assertIn("passenger.confirm()", self.src)

    def test_reject_cancela_com_motivo(self):
        self.assertIn("passenger.cancel(reason.trim())", self.src)

    def test_publica_evento_e_salva(self):
        self.assertIn("new RideDecisionEvent(", self.src)
        self.assertIn("eventPublisher.publishEvent(", self.src)
        self.assertIn("rideRepository.save(ride)", self.src)
        self.assertIn("ridePassengerRepository.save(passenger)", self.src)


class TestNotificationService(unittest.TestCase):
    def setUp(self):
        self.src = read(
            RIDES_MAIN / "service/NotificationService.java")

    def test_listener_transacional(self):
        self.assertIn("public void handleRideDecision(RideDecisionEvent",
                      self.src)
        idx = self.src.index("public void handleRideDecision(")
        antes = self.src[max(0, idx - 200):idx]
        self.assertIn("@EventListener", antes)
        self.assertIn("@Transactional", antes)

    def test_notifica_passageiro(self):
        self.assertIn("notification.setRecipientSub(event.passengerSub())",
                      self.src)
        self.assertIn("notification.setRideId(event.rideId())", self.src)

    def test_mensagens_aceite_e_recusa(self):
        self.assertIn("foi ACEITA.", self.src)
        self.assertIn("foi RECUSADA. Motivo: ", self.src)
        self.assertIn("event.decision() == RideDecision.ACCEPT", self.src)

    def test_truncamento_500(self):
        self.assertIn("message.length() > 500", self.src)
        self.assertIn("message.substring(0, 500)", self.src)


class TestSemMigrationNova(unittest.TestCase):
    def test_nao_cria_v9(self):
        versoes = sorted(p.name.split("__")[0] for p in MIG.glob("V*.sql"))
        self.assertEqual(["V2", "V7", "V8", "V9"], versoes,
                         "S2-02 não cria migration (CANCELLED reutilizado); V9 pertence à S2-03")

    def test_enum_status_inalterado(self):
        status_file = next(
            (ROOT / "raiji-common").glob("**/enums/RidePassengerStatus.java"))
        src = read(status_file)
        self.assertNotIn("REJECTED", src)
        for v in ("PENDING", "CONFIRMED", "CANCELLED", "COMPLETED"):
            self.assertIn(v, src)


class TestUnitTests(unittest.TestCase):
    def setUp(self):
        self.src = read(
            RIDES_TEST / "service/RidePassengerServiceTest.java")

    def test_sete_testes_do_decide(self):
        for nome in ("deveConfirmarSolicitacaoNoAccept",
                     "deveCancelarComMotivoNoReject",
                     "deveRejeitarRejectSemMotivo",
                     "deveRejeitarDecisaoPorOutroMotorista",
                     "deveRejeitarSolicitacaoJaDecidida",
                     "deveRejeitarAcceptSemVagaDisponivel",
                     "deveRejeitarSolicitacaoInexistenteNaDecisao"):
            self.assertIn(nome, self.src)

    def test_asserts_principais(self):
        self.assertIn("service.decide(", self.src)
        self.assertIn("RidePassengerStatus.CONFIRMED", self.src)
        self.assertIn("RidePassengerStatus.CANCELLED", self.src)
        self.assertIn("getCancellationReason()", self.src)
        self.assertIn("any(RideDecisionEvent.class)", self.src)

    def test_nao_usa_setter_inexistente(self):
        # RidePassenger não tem setStatus — status nasce PENDING e as
        # transições acontecem apenas via confirm()/cancel()
        self.assertNotIn("passenger.setStatus(", self.src)


class TestRideDecisionApiIT(unittest.TestCase):
    def setUp(self):
        self.src = read(APP_IT / "RideDecisionApiIT.java")

    def test_classe_e_estilo(self):
        self.assertIn("class RideDecisionApiIT extends RaijiTestSupport",
                      self.src)
        self.assertGreaterEqual(self.src.count("@Test"), 13)

    def test_rota_patch_usada(self):
        self.assertIn('.when().patch(app("/api/rides/" + rideId', self.src)

    def test_criterios_de_aceite(self):
        # statuses exigidos pela issue
        for status in (".statusCode(200)", ".statusCode(400)",
                       ".statusCode(403)", ".statusCode(404)",
                       ".statusCode(409)", ".statusCode(401)"):
            self.assertIn(status, self.src)
        # contrato da resposta
        self.assertIn('.body("status", equalTo("CONFIRMED"))', self.src)
        self.assertIn('.body("status", equalTo("CANCELLED"))', self.src)
        self.assertIn('.body("confirmedAt", notNullValue())', self.src)
        self.assertIn('.body("confirmedAt", nullValue())', self.src)

    def test_bagagem_visivel_antes_da_decisao(self):
        self.assertIn('.body("[0].baggage", hasSize(1))', self.src)

    def test_vagas_e_notificacoes_no_banco(self):
        self.assertIn("SELECT available_seats FROM rides", self.src)
        self.assertIn("SELECT COUNT(*) FROM notifications", self.src)
        self.assertIn("recipient_sub = ?", self.src)
        self.assertIn("cancellation_reason", self.src)


class TestCoexistenciaGeradores(unittest.TestCase):
    def test_setup_s2_01_nao_regrede_decide(self):
        # setup-s2-01.sh sobrescreve RidePassengerService.java inteiro;
        # com a S2-02 aplicada ele precisa detectar o decide() e pular a
        # regravação (composição idempotente dos geradores).
        s201 = ROOT / "scripts/setup-s2-01.sh"
        if not s201.is_file():
            self.skipTest("setup-s2-01.sh ausente neste checkout")
        src = read(s201)
        self.assertIn("não regredir", src)
        self.assertIn('grep -q "public RideDecisionResponse decide("', src)


class TestDocumentacao(unittest.TestCase):
    def test_api_md_atualizado(self):
        src = read(ROOT / "docs/API.md")
        self.assertIn("Decisao do motorista (S2-02)", src)
        self.assertIn("PATCH /api/rides/{rideId}/requests/{requestId}", src)
        for item in ("ACCEPT", "REJECT", "reason", "CANCELLED",
                     "CONFIRMED", "confirmedAt", "`409`", "`403`",
                     "NotificationService"):
            self.assertIn(item, src)


if __name__ == "__main__":
    unittest.main(verbosity=2)
