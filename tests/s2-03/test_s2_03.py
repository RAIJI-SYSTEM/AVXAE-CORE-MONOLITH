#!/usr/bin/env python3
"""Verificação estrutural da S2-03 (RN03/RN12) — trava de capacidade.

Roda SEM Maven/Docker: valida que o gerador criou/patcheou todos os
arquivos exigidos pela issue #10 e que os critérios de aceite estão
cobertos por testes. Para a validação real (compilação + testes + IT):

    bash tests/s2-03/run-maven.sh
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
            MIG / "V9__add_baggage_weight.sql",
            ROOT / "docs/ARQUITETURA.md",
            APP_IT / "CapacityLockApiIT.java",
            ROOT / "scripts/setup-s2-03.sh",
            ROOT / "tests/s2-03/run-all.sh",
            ROOT / "tests/s2-03/run-maven.sh",
            ROOT / "tests/s2-03/test_s2_03.py",
        ]
        for p in esperados:
            self.assertTrue(p.is_file(), f"ausente: {p}")


class TestMigrationV9(unittest.TestCase):
    def test_weight_kg_opcional_e_positivo(self):
        src = read(MIG / "V9__add_baggage_weight.sql")
        self.assertIn("ALTER TABLE baggage ADD COLUMN IF NOT EXISTS weight_kg NUMERIC(10,2)", src)
        self.assertIn("weight_kg IS NULL OR weight_kg > 0", src)

    def test_sequencia_de_migrations(self):
        versoes = sorted(p.name.split("__")[0] for p in MIG.glob("V*.sql"))
        self.assertEqual(["V2", "V7", "V8", "V9"], versoes)


class TestBaggageEntity(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "domain/Baggage.java")

    def test_campo_weight_kg(self):
        self.assertIn('@Column(name = "weight_kg", precision = 10, scale = 2)', self.src)
        self.assertIn("private BigDecimal weightKg;", self.src)
        self.assertIn("import java.math.BigDecimal;", self.src)

    def test_getter_setter(self):
        self.assertIn("public BigDecimal getWeightKg()", self.src)
        self.assertIn("public void setWeightKg(BigDecimal v)", self.src)


class TestBaggageDTOs(unittest.TestCase):
    def test_request_weight_kg_opcional(self):
        src = read(RIDES_MAIN / "dto/BaggageItemRequest.java")
        self.assertIn("BigDecimal weightKg,", src)
        self.assertIn("@Positive(message = \"O peso (weightKg)", src)
        self.assertIn("@Digits(integer = 8, fraction = 2", src)
        self.assertNotIn("@NotNull(message = \"O peso", src,
                         "weightKg deve permanecer OPCIONAL (contrato S2-01)")

    def test_response_expoe_weight_kg(self):
        src = read(RIDES_MAIN / "dto/BaggageItemResponse.java")
        self.assertIn("BigDecimal weightKg,", src)
        self.assertIn("baggage.getWeightKg(),", src)


class TestRideRepositoryLock(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "repository/RideRepository.java")

    def test_lock_pessimista(self):
        self.assertIn("@Lock(LockModeType.PESSIMISTIC_WRITE)", self.src)
        self.assertIn('@Query("select r from Ride r where r.id = :id")', self.src)
        self.assertIn('Optional<Ride> findByIdForUpdate(@Param("id") UUID id);', self.src)
        self.assertIn("import jakarta.persistence.LockModeType;", self.src)


class TestServiceCapacidade(unittest.TestCase):
    def setUp(self):
        self.src = read(RIDES_MAIN / "service/RidePassengerService.java")

    def test_veiculo_injetado(self):
        self.assertIn("private final VehicleRepository vehicleRepository;", self.src)
        self.assertIn("VehicleRepository vehicleRepository", self.src)
        self.assertIn("this.vehicleRepository = vehicleRepository;", self.src)

    def test_decide_usa_lock_pessimista(self):
        self.assertIn("rideRepository.findByIdForUpdate(rideId)", self.src)

    def test_capacidade_chamada_no_accept(self):
        self.assertIn("validarCapacidadeCarga(ride, passenger);", self.src)
        idx_accept = self.src.index("if (decision == RideDecision.ACCEPT)")
        idx_cap = self.src.index("validarCapacidadeCarga(ride, passenger);")
        idx_confirm = self.src.index("passenger.confirm();", idx_accept)
        self.assertLess(idx_accept, idx_cap, "validação deve estar no ramo ACCEPT")
        self.assertLess(idx_cap, idx_confirm, "capacidade deve ser validada antes do confirm()")

    def test_soma_peso_e_volume(self):
        self.assertIn("p.getStatus() == RidePassengerStatus.CONFIRMED", self.src)
        self.assertIn("ehCandidato", self.src)
        self.assertIn("item.getWeightKg()", self.src)
        self.assertIn(".multiply(BigDecimal.valueOf(item.getQuantity()))", self.src)
        self.assertIn("divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP)", self.src)

    def test_limites_e_400(self):
        self.assertIn("cargo.hasCargoCapacity()", self.src)
        self.assertIn("pesoTotal.compareTo(cargo.getMaxWeightKg()) > 0", self.src)
        self.assertIn("excede o limite da carona", self.src)
        self.assertIn("vehicle.getCargoVolumeLiters() > 0", self.src)
        self.assertIn("excede a capacidade do veículo", self.src)
        self.assertIn("HttpStatus.BAD_REQUEST", self.src)

    def test_request_persiste_peso(self):
        self.assertIn("baggageEntity.setWeightKg(item.weightKg());", self.src)


class TestUnitTests(unittest.TestCase):
    def setUp(self):
        self.src = read(
            RIDES_TEST / "service/RidePassengerServiceTest.java")

    def test_sete_testes_de_capacidade(self):
        for nome in ("deveUsarLockPessimistaAoDecidir",
                     "deveAceitarPesoExatamenteNoLimite",
                     "deveRejeitarPesoUmAcimaDoLimite",
                     "deveSomarBagagensConfirmadasAoDecidir",
                     "deveAceitarVolumeExatamenteNoLimite",
                     "deveRejeitarVolumeAcimaDaCapacidade",
                     "deveIgnorarTravaDeCargaSemCapacidadeDefinida"):
            self.assertIn(nome, self.src)

    def test_lock_verificado_e_veiculo_mockado(self):
        self.assertIn("verify(rideRepository).findByIdForUpdate(rideId);", self.src)
        self.assertIn("vehicleRepository = mock(VehicleRepository.class);", self.src)
        self.assertIn("when(rideRepository.findByIdForUpdate(rideId))", self.src)
        self.assertIn("vehicle.setCargoVolumeLiters(400);", self.src)

    def test_limites_nos_fixtures(self):
        self.assertIn('comCargoDetails("20")', self.src)
        self.assertIn('bagagem("10", 2, 40, 30, 20)', self.src)   # == limite
        self.assertIn('bagagem("20.5", 1, 40, 30, 20)', self.src) # 1 acima
        self.assertIn("bagagem(null, 2, 50, 40, 25)", self.src)   # 100 L == limite
        self.assertIn("bagagem(null, 2, 50, 40, 26)", self.src)   # 104 L > 100 L

    def test_candidato_entra_na_soma(self):
        # o helper precisa incluir o candidato na listagem stubada — em
        # produção ele consta na relação da carona dentro da transação
        self.assertIn("thenReturn(List.of(passenger));", self.src)
        self.assertIn("List.of(confirmado, pendente)", self.src)

    def test_construtor_bagagem_atualizado(self):
        self.assertIn('60, 40, 30, null, "Mala de bordo"', self.src)


class TestControllerTestAtualizado(unittest.TestCase):
    def test_construtor_response_com_weight_kg(self):
        src = read(RIDES_TEST / "web/RideRequestControllerTest.java")
        self.assertIn('60, 40, 30, null, "Mala de bordo"', src)


class TestCapacityLockApiIT(unittest.TestCase):
    def setUp(self):
        self.src = read(APP_IT / "CapacityLockApiIT.java")

    def test_classe_e_estilo(self):
        self.assertIn("class CapacityLockApiIT extends RaijiTestSupport", self.src)
        self.assertGreaterEqual(self.src.count("@Test"), 9)

    def test_concorrencia_com_executor_service(self):
        for item in ("ExecutorService", "Executors.newFixedThreadPool",
                     "CyclicBarrier", "Future<Integer>",
                     "ridePassengerService.decide(", "RideDecision.ACCEPT",
                     "ResponseStatusException"):
            self.assertIn(item, self.src)

    def test_asserts_da_corrida(self):
        self.assertIn("c == 200", self.src)
        self.assertIn("c == 409", self.src)
        self.assertIn("exatamente uma confirmação pode passar", self.src)
        self.assertIn("UPDATE rides SET available_seats = 1", self.src)
        self.assertIn("SELECT available_seats FROM rides", self.src)
        self.assertIn("vagas nunca podem ficar negativas", self.src)
        self.assertIn("status = 'CONFIRMED'", self.src)

    def test_limites_de_peso_e_volume(self):
        self.assertIn('Map.of("maxWeightKg", 20)', self.src)
        self.assertIn('itemBagagem("10", 2, 40, 30, 20)', self.src)   # exato
        self.assertIn('itemBagagem("20.5", 1, 40, 30, 20)', self.src) # 1 acima
        self.assertIn("criarMotorista(100)", self.src)
        self.assertIn("itemBagagem(null, 2, 50, 40, 25)", self.src)   # 100 L exato
        self.assertIn("itemBagagem(null, 2, 50, 40, 26)", self.src)   # 104 L acima
        self.assertIn('Map.of("maxWeightKg", 30)', self.src)          # soma CONFIRMED

    def test_sem_capacidade_ignora_trava(self):
        self.assertIn("criarMotorista(null)", self.src)
        self.assertIn("semCapacidadeDefinidaIgnoraTrava", self.src)

    def test_weight_kg_na_declaracao(self):
        self.assertIn('.statusCode(400)', self.src)
        self.assertIn('itemBagagem("0", 1, 40, 30, 20)', self.src)
        self.assertIn('itemBagagem("-1.5", 1, 40, 30, 20)', self.src)
        self.assertIn('itemBagagem("1.234", 1, 40, 30, 20)', self.src)
        self.assertIn('equalTo(12.5f)', self.src)


class TestArquiteturaDoc(unittest.TestCase):
    def setUp(self):
        self.src = read(ROOT / "docs/ARQUITETURA.md")

    def test_decisao_documentada(self):
        for item in ("PESSIMISTIC_WRITE", "SELECT ... FOR UPDATE",
                     "@Version", "AuditableEntity", "RN03", "RN12",
                     "409", "400", "ExecutorService", "CyclicBarrier",
                     "rollback", "issue #10", "available_seats",
                     "cargo_max_weight_kg", "cargo_volume_liters"):
            self.assertIn(item, self.src)


class TestDocumentacaoApi(unittest.TestCase):
    def test_api_md_atualizado(self):
        src = read(ROOT / "docs/API.md")
        self.assertIn("baggage[].weightKg", src)
        self.assertIn("S2-03", src)
        self.assertIn("peso/volume acima da capacidade (S2-03)", src)
        self.assertIn("docs/ARQUITETURA.md", src)


class TestCoexistenciaGeradores(unittest.TestCase):
    def test_setup_s2_01_protege_weight_kg(self):
        s201 = ROOT / "scripts/setup-s2-01.sh"
        if not s201.is_file():
            self.skipTest("setup-s2-01.sh ausente neste checkout")
        src = read(s201)
        self.assertIn("weightKg da S2-03 presente", src)
        self.assertIn("compat S2-03 presente", src)
        self.assertIn("não regredir", src)

    def test_setup_s2_02_protege_suite(self):
        s202 = ROOT / "scripts/setup-s2-02.sh"
        if not s202.is_file():
            self.skipTest("setup-s2-02.sh ausente neste checkout")
        self.assertIn("compat S2-03 presente", read(s202))

    def test_suite_s2_02_aceita_v9(self):
        t202 = ROOT / "tests/s2-02/test_s2_02.py"
        if not t202.is_file():
            self.skipTest("tests/s2-02 ausente neste checkout")
        self.assertIn('"V9"', read(t202))

    def test_suite_s2_01_aceita_weight_kg(self):
        t201 = ROOT / "tests/s2-01/test_s2_01.py"
        if not t201.is_file():
            self.skipTest("tests/s2-01 ausente neste checkout")
        self.assertIn("weightKg opcional desde a S2-03", read(t201))


if __name__ == "__main__":
    unittest.main(verbosity=2)
