#!/usr/bin/env python3
"""Verificação estrutural da S2-01 (RF18) — declaração de bagagem.

Roda SEM Maven/Docker: valida que o gerador criou/patcheou todos os
arquivos exigidos pela issue e que os critérios de aceite estão cobertos
por testes. Para a validação real (compilação + testes + IT), use:

    bash tests/s2-01/run-maven.sh
"""
import re
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
    def test_arquivos_exigem_existir(self):
        esperados = [
            RIDES_MAIN / "domain/BaggageType.java",
            RIDES_MAIN / "domain/Baggage.java",
            RIDES_MAIN / "dto/BaggageItemRequest.java",
            RIDES_MAIN / "dto/BaggageItemResponse.java",
            MIG / "V8__create_baggage.sql",
            APP_IT / "BaggageApiIT.java",
            ROOT / "scripts/setup-s2-01.sh",
            ROOT / "tests/s2-01/run-all.sh",
            ROOT / "tests/s2-01/run-maven.sh",
            ROOT / "tests/s2-01/test_s2_01.py",
        ]
        for p in esperados:
            self.assertTrue(p.is_file(), f"ausente: {p}")


class TestBaggageType(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "domain/BaggageType.java")

    def test_enum_com_quatro_tipos(self):
        for t in ("MOCHILA", "SACOLA", "MALA", "CAIXA"):
            self.assertRegex(self.src, rf"\b{t}\b")

    def test_json_minúsculo(self):
        self.assertIn("@JsonValue", self.src)
        self.assertIn("toLowerCase", self.src)

    def test_json_creator_case_insensitive(self):
        self.assertIn("@JsonCreator", self.src)


class TestEntidadeBaggage(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "domain/Baggage.java")

    def test_tabela_e_fk(self):
        self.assertIn('@Table(name = "baggage"', self.src)
        self.assertIn("@ManyToOne", self.src)
        self.assertIn('@JoinColumn(name = "ride_passenger_id"', self.src)

    def test_enum_string_e_auditoria(self):
        self.assertIn("@Enumerated(EnumType.STRING)", self.src)
        self.assertIn("extends AuditableEntity", self.src)

    def test_campos_obrigatórios(self):
        for campo in ("quantity", "lengthCm", "widthCm", "heightCm", "description"):
            self.assertIn(campo, self.src)


class TestDtos(unittest.TestCase):
    def test_request_validado(self):
        src = read(RIDES_MAIN / "dto/BaggageItemRequest.java")
        self.assertIn("record BaggageItemRequest", src)
        self.assertIn("@NotNull", src)
        self.assertIn("@Min(value = 1", src)
        self.assertIn(src.count("@Positive"), (3, 4),
                      "3 dimensões > 0 (+weightKg opcional desde a S2-03)")

    def test_response_com_from(self):
        src = read(RIDES_MAIN / "dto/BaggageItemResponse.java")
        self.assertIn("record BaggageItemResponse", src)
        self.assertIn("static BaggageItemResponse from(Baggage", src)

    def test_create_request_baggage_obrigatório(self):
        src = read(RIDES_MAIN / "dto/RideRequestCreateRequest.java")
        self.assertIn("@NotEmpty", src)
        self.assertIn("@Valid", src)
        self.assertIn("List<BaggageItemRequest> baggage", src)

    def test_response_expõe_bagagem(self):
        src = read(RIDES_MAIN / "dto/RidePassengerResponse.java")
        self.assertIn("List<BaggageItemResponse> baggage", src)
        self.assertIn("BaggageItemResponse::from", src)


class TestMigracaoV8(unittest.TestCase):
    def setUp(self):
        self.src = read(MIG / "V8__create_baggage.sql")

    def test_cria_apenas_baggage(self):
        tabelas = re.findall(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+)", self.src, re.I)
        self.assertEqual([t.lower() for t in tabelas], ["baggage"],
                         "S2-01: tabela única (sem tabela por tipo)")

    def test_fk_para_ride_passengers(self):
        self.assertRegex(self.src, r"REFERENCES\s+ride_passengers\s*\(id\)")

    def test_colunas_de_auditoria(self):
        for col in ("created_by", "created_at", "updated_by", "updated_at",
                    "deleted_at", "version"):
            self.assertIn(col, self.src)

    def test_checks_de_domínio(self):
        low = self.src.lower()
        for tipo in ("'mochila'", "'sacola'", "'mala'", "'caixa'"):
            self.assertIn(tipo, low)
        self.assertRegex(low, r"check\s*\(quantity\s*>=\s*1\)")
        self.assertRegex(low, r"check\s*\(length_cm\s*>\s*0")

    def test_v2_nao_foi_tocada(self):
        v2 = [p for p in MIG.glob("V2__*.sql")]
        self.assertTrue(v2, "V2 deve existir")
        self.assertNotIn("baggage", read(v2[0]).lower())


class TestDominioETransacao(unittest.TestCase):
    def test_one_to_many_cascade(self):
        src = read(RIDES_MAIN / "domain/RidePassenger.java")
        self.assertIn("@OneToMany", src)
        self.assertIn('mappedBy = "ridePassenger"', src)
        self.assertIn("CascadeType.ALL", src)
        self.assertIn("orphanRemoval = true", src)
        self.assertIn("public void addBaggage(Baggage", src)
        self.assertIn("item.setRidePassenger(this)", src)

    def test_service_persiste_na_mesma_transacao(self):
        src = read(RIDES_MAIN / "service/RidePassengerService.java")
        self.assertIn("List<BaggageItemRequest> baggage", src)
        self.assertIn("@Transactional", src)
        self.assertIn("passenger.addBaggage(", src)
        self.assertIn("ridePassengerRepository.save(passenger)", src)
        # 400 defensivo ANTES de qualquer consulta
        idx_bag = src.index("baggage == null || baggage.isEmpty()")
        idx_ride = src.index("rideRepository.findById(rideId)")
        self.assertLess(idx_bag, idx_ride,
                        "validação de bagagem deve preceder o findById")

    def test_controller_repassa_bagagem(self):
        src = read(RIDES_MAIN / "web/RideRequestController.java")
        self.assertIn("request.baggage()", src)
        self.assertIn("@Valid @RequestBody RideRequestCreateRequest", src)


class TestBaggageApiIT(unittest.TestCase):
    def setUp(self):
        self.src = read(APP_IT / "BaggageApiIT.java")

    def test_critérios_de_aceite_cobertos(self):
        for metodo in (
            "solicitacaoSemBaggageRetorna400",
            "solicitacaoComBaggageVazioRetorna400",
            "quantityInvalidaRetorna400",
            "dimensoesInvalidasRetornam400",
            "typeForaDoEnumRetorna400",
            "bagagemPersisteEhRetornada",
            "motoristaVeBagagemAntesDeDecidir",
            "multiplosItensPersistem",
            "typeCaseInsensitiveEntradaMinusculaSaida",
        ):
            self.assertIn(metodo, self.src, f"teste ausente: {metodo}")

    def test_usa_jdbcTemplate_para_persistencia(self):
        self.assertIn("JdbcTemplate", self.src)
        self.assertIn("FROM baggage", self.src)
        self.assertIn("ride_passenger_id", self.src)

    def test_motorista_vê_bagagem_com_status_pending(self):
        self.assertIn('equalTo("PENDING")', self.src)
        self.assertIn("[0].baggage", self.src)


class TestesExistentesPatcheados(unittest.TestCase):
    def test_ride_request_api_it(self):
        src = read(APP_IT / "RideRequestApiIT.java")
        self.assertIn("payloadSolicitacao(", src)
        self.assertIn('put("baggage"', src)
        self.assertNotIn('Map.of("passengerName", "Maria Silva")', src,
                         "payloads antigos sem bagagem devem ter sido substituídos")
        self.assertIn('[0].baggage", hasSize(1)', src)

    def test_controller_test(self):
        src = read(RIDES_TEST / "web/RideRequestControllerTest.java")
        self.assertIn("baggageAusenteRetorna400", src)
        self.assertIn("baggageVazioRetorna400", src)
        self.assertIn("BaggageItemResponse", src)
        self.assertIn("any()", src)
        self.assertEqual(src.count('"baggage": ['), 4,
                         "3 payloads com bagagem válida + 1 array vazio")
        self.assertIn('jsonPath("$.baggage[0].type").value("mala")', src)

    def test_service_test(self):
        src = read(RIDES_TEST / "service/RidePassengerServiceTest.java")
        self.assertIn("bagagemValida()", src)
        self.assertIn("devePersistirBagagemNaMesmaTransacao", src)
        self.assertIn("deveRejeitarBagagemAusenteOuVazia", src)
        self.assertIn("ArgumentCaptor", src)
        self.assertIn("assertSame", src)
        self.assertNotIn('PASSENGER_SUB, "João")', src,
                         "nenhuma chamada 3-args pode restar")


class TestDocumentacao(unittest.TestCase):
    def test_api_md_atualizado(self):
        src = read(ROOT / "docs/API.md")
        self.assertIn("Solicitacao de vaga (S1-06 + S2-01)", src)
        for item in ("POST /api/rides/{rideId}/requests",
                     "GET /api/rides/{rideId}/requests",
                     "mochila", "sacola", "mala", "caixa",
                     "lengthCm", "widthCm", "heightCm"):
            self.assertIn(item, src)
        self.assertIn("S2-03", src, "nota sobre peso/volume adiados")


if __name__ == "__main__":
    unittest.main(verbosity=2)
