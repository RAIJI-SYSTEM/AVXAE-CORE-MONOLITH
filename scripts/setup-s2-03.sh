#!/usr/bin/env bash
# =============================================================================
# setup-s2-03.sh — S2-03 · Trava de capacidade de assentos e carga (RN03/RN12)
# =============================================================================
# Script ÚNICO e autocontido que implementa toda a issue #10 no projeto:
#
#   NOVOS:
#   * raiji-rides/.../db/migration/V9__add_baggage_weight.sql (weight_kg opcional)
#   * docs/ARQUITETURA.md            (decisão: lock pessimista + @Version)
#   * raiji-app/.../it/CapacityLockApiIT.java (concorrência + limites)
#   * tests/s2-03/*                  (suíte de verificação)
#
#   ATUALIZADOS:
#   * Baggage               -> weightKg (BigDecimal, opcional)
#   * BaggageItemRequest    -> weightKg @Positive @Digits (opcional — S2-01 intacto)
#   * BaggageItemResponse   -> expõe weightKg
#   * RideRepository        -> findByIdForUpdate @Lock(PESSIMISTIC_WRITE)
#   * RidePassengerService  -> decide() com FOR UPDATE + validarCapacidadeCarga
#                              (peso CONFIRMED ≤ maxWeightKg; volume ≤ cargoVolumeLiters)
#   * RidePassengerServiceTest -> +7 testes (limites exato/1 acima, lock, soma)
#   * RideRequestControllerTest -> construtor BaggageItemResponse (+weightKg)
#   * docs/API.md           -> weightKg + travas documentadas
#   * scripts/setup-s2-01.sh -> guardas: não regride Baggage/DTOs com weightKg
#   * tests/s2-02/test_s2_02.py -> migrations esperadas [V2,V7,V8,V9]
#
# Uso (a partir da RAIZ DO PROJETO):
#   bash setup-s2-03.sh           # implementa tudo (idempotente)
#   bash setup-s2-03.sh --check   # implementa e roda a verificação
# =============================================================================
set -euo pipefail

MODE="apply"
if [[ "${1:-}" == "--check" ]]; then MODE="check"; fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Detecta a RAIZ DO PROJETO com segurança ----------------------------------
ROOT=""
GIT_TOP="$(cd "$SCRIPT_DIR" && git rev-parse --show-toplevel 2>/dev/null || true)"
for cand in "$PWD" "$SCRIPT_DIR" "$SCRIPT_DIR/.." "$GIT_TOP"; do
  [[ -n "$cand" ]] || continue
  if [[ -f "$cand/pom.xml" && -f "$cand/docker-compose.yml" ]]; then
    ROOT="$(cd "$cand" && pwd)"
    break
  fi
done
if [[ -z "$ROOT" ]]; then
  echo "[setup-s2-03] ERRO: raiz do projeto não encontrada (pom.xml + docker-compose.yml)." >&2
  exit 1
fi
cd "$ROOT"
echo "[setup-s2-03] raiz do projeto: $ROOT"

SELF="$(readlink -f "${BASH_SOURCE[0]}")"
CANONICAL="$ROOT/scripts/setup-s2-03.sh"
mkdir -p scripts
if [[ "$SELF" != "$CANONICAL" ]]; then
  cp "$SELF" "$CANONICAL"
  echo "[setup-s2-03] gerador instalado em scripts/setup-s2-03.sh (local canônico)"
fi

GRN=$'\033[0;32m'; NC=$'\033[0m'
log() { printf '%s[setup-s2-03]%s %s\n' "$GRN" "$NC" "$*"; }

write_file() {
  mkdir -p "$(dirname "$1")"
  cat > "$1"
  log "gravado: $1"
}

RIDES_MAIN="raiji-rides/src/main/java/com/raiji/rides"
APP_IT="raiji-app/src/test/java/com/raiji/it"

# =============================================================================
# 1) V9 — peso declarado por item de bagagem (opcional, > 0 quando presente)
# =============================================================================
write_file "raiji-rides/src/main/resources/db/migration/V9__add_baggage_weight.sql" <<'@@V9_SQL@@'
-- S2-03 / RN12: peso declarado por item de bagagem.
-- Opcional (NULL permitido) para manter o contrato da S2-01 retrocompatível;
-- quando informado, deve ser > 0. A trava de capacidade soma
-- weight_kg × quantity das bagagens CONFIRMED (+ as da solicitação em aceite)
-- e compara com rides.cargo_max_weight_kg no PATCH de decisão.

ALTER TABLE baggage ADD COLUMN IF NOT EXISTS weight_kg NUMERIC(10,2);

ALTER TABLE baggage DROP CONSTRAINT IF EXISTS chk_baggage_weight;
ALTER TABLE baggage ADD CONSTRAINT chk_baggage_weight
    CHECK (weight_kg IS NULL OR weight_kg > 0);
@@V9_SQL@@

# =============================================================================
# 2) RideRepository — lock pessimista (SELECT ... FOR UPDATE) na carona
# =============================================================================
write_file "$RIDES_MAIN/repository/RideRepository.java" <<'@@RIDE_REPO@@'
package com.raiji.rides.repository;

import java.util.Optional;
import java.util.UUID;

import com.raiji.rides.domain.Ride;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideRepository extends JpaRepository<Ride, UUID>, JpaSpecificationExecutor<Ride> {

    /**
     * S2-03 / RN03 — trava pessimista ({@code SELECT ... FOR UPDATE}) na linha
     * da carona: serializa decisões concorrentes. Dois aceites simultâneos
     * sobre a última vaga => um confirma e o outro, ao reler a carona após o
     * commit do primeiro, recebe 409. Estratégia documentada em
     * docs/ARQUITETURA.md (o {@code @Version} herdado de AuditableEntity
     * permanece como rede de segurança).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Ride r where r.id = :id")
    Optional<Ride> findByIdForUpdate(@Param("id") UUID id);
}
@@RIDE_REPO@@

# =============================================================================
# 3) docs/ARQUITETURA.md — decisão de locking e regras de capacidade
# =============================================================================
write_file "docs/ARQUITETURA.md" <<'@@ARQ@@'
# Arquitetura — decisões registradas

## S2-03 · Trava de capacidade de assentos e carga (RN03 / RN12)

### Problema

Reservas confirmadas nunca podem exceder os assentos da carona nem a
capacidade de carga (peso e volume), **mesmo sob concorrência**: dois aceites
simultâneos da última vaga não podem ambos confirmar (a issue #10 pede
explicitamente testes de concorrência com ExecutorService).

### Decisão — lock pessimista na linha da carona

`RidePassengerService.decide()` (PATCH
`/api/rides/{rideId}/requests/{requestId}`) carrega a carona com
`RideRepository.findByIdForUpdate(...)`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select r from Ride r where r.id = :id")
Optional<Ride> findByIdForUpdate(@Param("id") UUID id);
```

- `SELECT ... FOR UPDATE` serializa as decisões sobre a mesma carona: a
  segunda transação aguarda o commit da primeira e então **relê**
  `available_seats` já decrementado → responde **409** em vez de
  sobrescrever o contador.
- Escolhido pela **simplicidade** (observação da issue #10): transação curta,
  uma única linha "quente" (a carona), sem necessidade de retry
  application-level sobre `OptimisticLockException`.
- O **lock otimista** (`@Version` herdado de `AuditableEntity`) permanece
  ativo em todas as entidades como rede de segurança contra escrita perdida
  nos demais fluxos (ex.: edição de carona), mas não é a linha de defesa do
  aceite — quem garante a serialização é o `FOR UPDATE`.

### Regras de capacidade aplicadas no ACCEPT

| Trava | Regra | Falha |
|---|---|---|
| Assentos | `rides.available_seats > 0` antes de decrementar | 409 |
| Peso | Σ `weight_kg × quantity` das bagagens **CONFIRMED** (+ as do candidato) ≤ `rides.cargo_max_weight_kg` | 400 |
| Volume | Σ `length_cm × width_cm × height_cm × quantity ÷ 1000` (litros) ≤ `vehicles.cargo_volume_liters` do veículo da carona | 400 |

- Peso e volume só são verificados **se existir** limite declarado
  (`cargoDetails.maxWeightKg > 0` na carona / `cargoVolumeLiters > 0` no
  veículo) — sem limite declarado, a trava é ignorada (caronas e veículos
  antigos continuam válidos).
- `weightKg` é **opcional** na declaração de bagagem (contrato da S2-01
  permanece retrocompatível); item sem peso contribui 0 kg na soma.
- Solicitações PENDING **não** reservam capacidade: entram na soma apenas as
  bagagens CONFIRMED e as da solicitação em aceite.
- Exceder o limite é erro de requisição (**400**, critério de aceite da
  issue); esgotar assentos é conflito de estado (**409**).

### Consistência

O `decide()` roda em uma única transação: lock → validações (dono, PENDING,
vagas, peso, volume) → decremento de vagas → `confirm()`/`cancel()` → evento
de decisão (notificação). Qualquer falha faz **rollback completo** — nenhuma
reserva fica em estado inconsistente e `available_seats` nunca fica negativo.

### Testes

- `CapacityLockApiIT.confirmacoesSimultaneasNaoEstouramLotacao`:
  `ExecutorService` + `CyclicBarrier` disparam 2 aceites simultâneos sobre a
  última vaga → exatamente 1× 200 e 1× 409, `available_seats = 0`, 1 reserva
  CONFIRMED.
- Limites: peso/volume **exatamente no limite** (aceito) e **1 acima**
  (400), além da soma acumulada entre passageiros CONFIRMED.
- Unitários (`RidePassengerServiceTest`): mesmas fronteiras com mocks +
  verificação do uso de `findByIdForUpdate`.
@@ARQ@@

# =============================================================================
# 4) PATCHes nos arquivos existentes (python — âncoras exatas + guards)
# =============================================================================
python3 - <<'@@PYPATCH@@'
import os
import sys

def load(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

def save(p, s):
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)

def patch(path, guard, patches, must_apply_all=True):
    src = load(path)
    if guard in src:
        print(f"  [skip] {path} (já patcheado)")
        return
    for old, new, count in patches:
        found = src.count(old)
        if found == 0:
            if must_apply_all:
                sys.exit(f"ERRO: anchor não encontrado em {path}:\n---\n{old[:300]}\n---")
            continue
        if count is not None and found != count:
            sys.exit(f"ERRO: anchor {found}x (esperado {count}x) em {path}:\n{old[:200]}")
        src = src.replace(old, new)
    save(path, src)
    print(f"  [ok]   {path}")

RIDES = "raiji-rides/src/main/java/com/raiji/rides"

# ---------------------------------------------------------------------------
# A) Baggage — weightKg (opcional)
# ---------------------------------------------------------------------------
BAG = f"{RIDES}/domain/Baggage.java"
patch(BAG, "weightKg", [
    ('''import java.util.UUID;

import com.raiji.common.base.AuditableEntity;''',
     '''import java.math.BigDecimal;
import java.util.UUID;

import com.raiji.common.base.AuditableEntity;''', 1),
    (''' * tipo). A soma de peso/volume para validação contra a capacidade da carona
 * será implementada em S2-03.''',
     ''' * tipo). Desde a S2-03 (RN12) o item pode declarar {@code weightKg}
 * (opcional); a soma de peso/volume das bagagens CONFIRMED é travada no
 * aceite da solicitação.''', 1),
    ('''    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
''',
     '''    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** S2-03 / RN12 — peso unitário declarado em kg (opcional; > 0 quando presente). */
    @Column(name = "weight_kg", precision = 10, scale = 2)
    private BigDecimal weightKg;
''', 1),
    ('''    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
}''',
     '''    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal v) { this.weightKg = v; }
}''', 1),
])

# ---------------------------------------------------------------------------
# B) BaggageItemRequest — weightKg opcional (@Positive + @Digits)
# ---------------------------------------------------------------------------
BREQ = f"{RIDES}/dto/BaggageItemRequest.java"
patch(BREQ, "weightKg", [
    ('''import com.raiji.rides.domain.BaggageType;

import jakarta.validation.constraints.Min;''',
     '''import com.raiji.rides.domain.BaggageType;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;''', 1),
    ('''        Integer heightCm,

        @Size(max = 500, message = "A descrição deve ter no máximo 500 caracteres.")
        String description''',
     '''        Integer heightCm,

        @Positive(message = "O peso (weightKg) deve ser maior que zero quando informado.")
        @Digits(integer = 8, fraction = 2, message = "O peso deve ter no máximo 8 dígitos e 2 casas decimais.")
        BigDecimal weightKg,

        @Size(max = 500, message = "A descrição deve ter no máximo 500 caracteres.")
        String description''', 1),
])

# ---------------------------------------------------------------------------
# C) BaggageItemResponse — expõe weightKg
# ---------------------------------------------------------------------------
BRES = f"{RIDES}/dto/BaggageItemResponse.java"
patch(BRES, "weightKg", [
    ('''import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.BaggageType;''',
     '''import java.math.BigDecimal;

import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.BaggageType;''', 1),
    ('''        Integer heightCm,
        String description
) {''',
     '''        Integer heightCm,
        BigDecimal weightKg,
        String description
) {''', 1),
    ('''                baggage.getHeightCm(),
                baggage.getDescription()''',
     '''                baggage.getHeightCm(),
                baggage.getWeightKg(),
                baggage.getDescription()''', 1),
])

# ---------------------------------------------------------------------------
# D) RidePassengerService — lock pessimista + trava de capacidade no ACCEPT
# ---------------------------------------------------------------------------
SVC = f"{RIDES}/service/RidePassengerService.java"

capacity_methods = '''
    /**
     * S2-03 / RN12 — trava de capacidade de carga no ACCEPT.
     *
     * <p>Soma o peso (weightKg × quantity) e o volume
     * (lengthCm × widthCm × heightCm × quantity ÷ 1000, em litros) das
     * bagagens CONFIRMED na carona + as da solicitação em aceite, e compara
     * com cargoDetails.maxWeightKg (carona) e cargoVolumeLiters (veículo),
     * quando declarados. Excedeu => 400; sem limite declarado => ignora
     * ("se existir", critério da issue #10).
     */
    private void validarCapacidadeCarga(Ride ride, RidePassenger candidato) {
        List<RidePassenger> passageiros = ridePassengerRepository
                .findByRideIdAndDeletedAtIsNull(ride.getId());

        BigDecimal pesoTotal = BigDecimal.ZERO;
        BigDecimal volumeTotalLitros = BigDecimal.ZERO;

        for (RidePassenger p : passageiros) {
            boolean confirmado =
                    p.getStatus() == RidePassengerStatus.CONFIRMED;
            boolean ehCandidato = p.getId() != null
                    && p.getId().equals(candidato.getId());
            if (!confirmado && !ehCandidato) {
                continue; // PENDING não reserva capacidade
            }
            for (Baggage item : p.getBaggage()) {
                pesoTotal = pesoTotal.add(pesoTotalDoItem(item));
                volumeTotalLitros =
                        volumeTotalLitros.add(volumeDoItemEmLitros(item));
            }
        }

        CargoDetails cargo = ride.getCargoDetails();
        if (cargo != null && cargo.hasCargoCapacity()
                && pesoTotal.compareTo(cargo.getMaxWeightKg()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Peso total das bagagens ("
                            + pesoTotal.stripTrailingZeros().toPlainString()
                            + " kg) excede o limite da carona ("
                            + cargo.getMaxWeightKg().stripTrailingZeros().toPlainString()
                            + " kg)."
            );
        }

        Vehicle vehicle = vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(
                        ride.getVehicleId(), ride.getDriverSub())
                .orElse(null);

        if (vehicle != null
                && vehicle.getCargoVolumeLiters() != null
                && vehicle.getCargoVolumeLiters() > 0
                && volumeTotalLitros.compareTo(
                        BigDecimal.valueOf(vehicle.getCargoVolumeLiters())) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Volume total das bagagens ("
                            + volumeTotalLitros.stripTrailingZeros().toPlainString()
                            + " L) excede a capacidade do veículo ("
                            + vehicle.getCargoVolumeLiters() + " L)."
            );
        }
    }

    /** Peso do item = weightKg × quantity; sem weightKg declarado => 0. */
    private static BigDecimal pesoTotalDoItem(Baggage item) {
        if (item.getWeightKg() == null) {
            return BigDecimal.ZERO;
        }
        return item.getWeightKg()
                .multiply(BigDecimal.valueOf(item.getQuantity()));
    }

    /** Volume do item = (lengthCm × widthCm × heightCm ÷ 1000) × quantity, em litros. */
    private static BigDecimal volumeDoItemEmLitros(Baggage item) {
        BigDecimal volumeUnitarioLitros = BigDecimal
                .valueOf((long) item.getLengthCm()
                        * item.getWidthCm()
                        * item.getHeightCm())
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        return volumeUnitarioLitros
                .multiply(BigDecimal.valueOf(item.getQuantity()));
    }
'''

svc_tail_old = '''        return RideDecisionResponse.from(saved);
    }
}'''

svc_tail_new = '''        return RideDecisionResponse.from(saved);
    }
''' + capacity_methods + '''}'''

patch(SVC, "validarCapacidadeCarga", [
    ('''import java.time.Instant;
import java.time.LocalDateTime;''',
     '''import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;''', 1),
    ('''import com.raiji.common.enums.RidePassengerStatus;''',
     '''import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RidePassengerStatus;''', 1),
    ('''import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;''',
     '''import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;
import com.raiji.users.domain.Vehicle;
import com.raiji.users.repository.VehicleRepository;''', 1),
    ('''    private final RideRepository rideRepository;
    private final RidePassengerRepository ridePassengerRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RidePassengerService(
            RideRepository rideRepository,
            RidePassengerRepository ridePassengerRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.rideRepository = rideRepository;
        this.ridePassengerRepository = ridePassengerRepository;
        this.eventPublisher = eventPublisher;
    }''',
     '''    private final RideRepository rideRepository;
    private final RidePassengerRepository ridePassengerRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final VehicleRepository vehicleRepository;

    public RidePassengerService(
            RideRepository rideRepository,
            RidePassengerRepository ridePassengerRepository,
            ApplicationEventPublisher eventPublisher,
            VehicleRepository vehicleRepository
    ) {
        this.rideRepository = rideRepository;
        this.ridePassengerRepository = ridePassengerRepository;
        this.eventPublisher = eventPublisher;
        this.vehicleRepository = vehicleRepository;
    }''', 1),
    ('''            baggageEntity.setHeightCm(item.heightCm());
            baggageEntity.setDescription(item.description());''',
     '''            baggageEntity.setHeightCm(item.heightCm());
            baggageEntity.setWeightKg(item.weightKg());
            baggageEntity.setDescription(item.description());''', 1),
    ('''        Ride ride = rideRepository.findById(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não tem permissão para decidir solicitações desta carona."''',
     '''        // S2-03 / RN03: SELECT ... FOR UPDATE — serializa decisões
        // concorrentes sobre a mesma carona (ver docs/ARQUITETURA.md).
        Ride ride = rideRepository.findByIdForUpdate(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não tem permissão para decidir solicitações desta carona."''', 1),
    ('''            ride.setAvailableSeats(ride.getAvailableSeats() - 1);
            passenger.confirm();
            decidedAt = passenger.getConfirmedAt();''',
     '''            // S2-03 / RN12: trava de capacidade de carga (peso + volume)
            validarCapacidadeCarga(ride, passenger);

            ride.setAvailableSeats(ride.getAvailableSeats() - 1);
            passenger.confirm();
            decidedAt = passenger.getConfirmedAt();''', 1),
    (svc_tail_old, svc_tail_new, 1),
])

# ---------------------------------------------------------------------------
# E) RidePassengerServiceTest — mock do veículo, stub do lock, +7 testes
# ---------------------------------------------------------------------------
SVCTEST = "raiji-rides/src/test/java/com/raiji/rides/service/RidePassengerServiceTest.java"

capacity_tests = '''
    // =========================================================================
    // S2-03 / RN03+RN12 — trava de capacidade (lock pessimista, peso, volume)
    // =========================================================================

    private static Baggage bagagem(String pesoKg, int quantidade,
                                   int lengthCm, int widthCm, int heightCm) {
        Baggage b = new Baggage();
        b.setType(BaggageType.MALA);
        b.setQuantity(quantidade);
        b.setLengthCm(lengthCm);
        b.setWidthCm(widthCm);
        b.setHeightCm(heightCm);
        if (pesoKg != null) {
            b.setWeightKg(new BigDecimal(pesoKg));
        }
        return b;
    }

    private RidePassenger solicitacaoPendenteComBagagem(Baggage... itens) {
        RidePassenger passenger = solicitacaoPendente();
        for (Baggage b : itens) {
            passenger.addBaggage(b);
        }
        // em produção o candidato consta na listagem da carona (mesma
        // transação); no teste unitário garantimos o mesmo comportamento
        when(passengerRepository.findByRideIdAndDeletedAtIsNull(rideId))
                .thenReturn(List.of(passenger));
        return passenger;
    }

    private RidePassenger confirmadoComBagagem(Baggage... itens) {
        RidePassenger passenger = new RidePassenger();
        ReflectionTestUtils.setField(passenger, "id", UUID.randomUUID());
        passenger.setRideId(rideId);
        passenger.setPassengerSub("confirmado-" + UUID.randomUUID());
        passenger.setPassengerName("Passageiro Confirmado");
        passenger.confirm();
        for (Baggage b : itens) {
            passenger.addBaggage(b);
        }
        return passenger;
    }

    private void comCargoDetails(String maxWeightKg) {
        CargoDetails cargo = new CargoDetails();
        cargo.setMaxWeightKg(new BigDecimal(maxWeightKg));
        ride.setCargoDetails(cargo);
    }

    @Test
    void deveUsarLockPessimistaAoDecidir() {
        RidePassenger pendente = solicitacaoPendente();

        service.decide(rideId, pendente.getId(), DRIVER_SUB,
                RideDecision.ACCEPT, null);

        verify(rideRepository).findByIdForUpdate(rideId);
    }

    @Test
    void deveAceitarPesoExatamenteNoLimite() {
        comCargoDetails("20");
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem("10", 2, 40, 30, 20)); // 10 kg × 2 = 20 kg == limite

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB, RideDecision.ACCEPT, null);

        assertEquals(RidePassengerStatus.CONFIRMED, response.status());
        verify(passengerRepository).save(any(RidePassenger.class));
    }

    @Test
    void deveRejeitarPesoUmAcimaDoLimite() {
        comCargoDetails("20");
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem("20.5", 1, 40, 30, 20)); // 20.5 kg > 20 kg

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveSomarBagagensConfirmadasAoDecidir() {
        comCargoDetails("30");
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem("16", 1, 40, 30, 20));
        RidePassenger confirmado = confirmadoComBagagem(
                bagagem("15", 1, 40, 30, 20));
        when(passengerRepository.findByRideIdAndDeletedAtIsNull(rideId))
                .thenReturn(List.of(confirmado, pendente)); // 15 + 16 = 31 kg > 30 kg

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveAceitarVolumeExatamenteNoLimite() {
        vehicle.setCargoVolumeLiters(100);
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem(null, 2, 50, 40, 25)); // 50 L × 2 = 100 L == limite

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB, RideDecision.ACCEPT, null);

        assertEquals(RidePassengerStatus.CONFIRMED, response.status());
    }

    @Test
    void deveRejeitarVolumeAcimaDaCapacidade() {
        vehicle.setCargoVolumeLiters(100);
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem(null, 2, 50, 40, 26)); // 52 L × 2 = 104 L > 100 L

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveIgnorarTravaDeCargaSemCapacidadeDefinida() {
        vehicle.setCargoVolumeLiters(null); // veículo sem capacidade declarada
        RidePassenger pendente = solicitacaoPendenteComBagagem(
                bagagem("9999", 9, 999, 999, 999)); // carona sem cargoDetails

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB, RideDecision.ACCEPT, null);

        assertEquals(RidePassengerStatus.CONFIRMED, response.status());
    }
'''

test_tail_old = '''        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }
}'''

test_tail_new = '''        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }
''' + capacity_tests + '''}'''

patch(SVCTEST, "deveUsarLockPessimistaAoDecidir", [
    ('''import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.BaggageType;''',
     '''import com.raiji.common.embeddable.CargoDetails;
import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.common.enums.RideStatus;
import com.raiji.rides.domain.Baggage;
import com.raiji.rides.domain.BaggageType;''', 1),
    ('''import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;''',
     '''import com.raiji.rides.repository.RidePassengerRepository;
import com.raiji.rides.repository.RideRepository;
import com.raiji.users.domain.Vehicle;
import com.raiji.users.repository.VehicleRepository;''', 1),
    ('''import java.time.LocalDateTime;
import java.util.List;''',
     '''import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;''', 1),
    ('''    private RideRepository rideRepository;
    private RidePassengerRepository passengerRepository;
    private ApplicationEventPublisher eventPublisher;
    private RidePassengerService service;''',
     '''    private RideRepository rideRepository;
    private RidePassengerRepository passengerRepository;
    private ApplicationEventPublisher eventPublisher;
    private VehicleRepository vehicleRepository;
    private RidePassengerService service;''', 1),
    ('''    private UUID rideId;
    private Ride ride;''',
     '''    private UUID rideId;
    private Ride ride;
    private Vehicle vehicle;''', 1),
    ('''        rideRepository = mock(RideRepository.class);
        passengerRepository = mock(RidePassengerRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new RidePassengerService(
                rideRepository,
                passengerRepository,
                eventPublisher
        );''',
     '''        rideRepository = mock(RideRepository.class);
        passengerRepository = mock(RidePassengerRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        vehicleRepository = mock(VehicleRepository.class);

        service = new RidePassengerService(
                rideRepository,
                passengerRepository,
                eventPublisher,
                vehicleRepository
        );''', 1),
    ('''        ride.setTotalSeats(4);
        ride.setAvailableSeats(4);

        when(rideRepository.findById(rideId))
                .thenReturn(Optional.of(ride));''',
     '''        ride.setTotalSeats(4);
        ride.setAvailableSeats(4);
        ride.setVehicleId(UUID.randomUUID());

        when(rideRepository.findById(rideId))
                .thenReturn(Optional.of(ride));

        when(rideRepository.findByIdForUpdate(rideId))
                .thenReturn(Optional.of(ride));

        vehicle = new Vehicle();
        vehicle.setOwnerSub(DRIVER_SUB);
        vehicle.setCargoVolumeLiters(400);
        when(vehicleRepository
                .findByIdAndOwnerSubAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(vehicle));''', 1),
    ('''        return List.of(new BaggageItemRequest(
                BaggageType.MALA, 1, 60, 40, 30, "Mala de bordo"));''',
     '''        return List.of(new BaggageItemRequest(
                BaggageType.MALA, 1, 60, 40, 30, null, "Mala de bordo"));''', 1),
    (test_tail_old, test_tail_new, 1),
])

# ---------------------------------------------------------------------------
# F) RideRequestControllerTest — construtor BaggageItemResponse (+weightKg)
# ---------------------------------------------------------------------------
CTEST = "raiji-rides/src/test/java/com/raiji/rides/web/RideRequestControllerTest.java"
patch(CTEST, "60, 40, 30, null", [
    ('''                List.of(new BaggageItemResponse(
                        BaggageType.MALA, 1, 60, 40, 30, "Mala de bordo"))''',
     '''                List.of(new BaggageItemResponse(
                        BaggageType.MALA, 1, 60, 40, 30, null, "Mala de bordo"))''', 1),
])

# ---------------------------------------------------------------------------
# G) docs/API.md — weightKg + travas de capacidade
# ---------------------------------------------------------------------------
DOCS = "docs/API.md"
patch(DOCS, "weightKg", [
    ('''| baggage[].heightCm | int | sim | > 0 |''',
     '''| baggage[].heightCm | int | sim | > 0 |
| baggage[].weightKg | decimal | nao | > 0 quando informado; ate 2 casas decimais (S2-03) |''', 1),
    ('''- `400` baggage ausente/vazio, quantity <= 0, dimensao <= 0, type fora do enum, nome invalido''',
     '''- `400` baggage ausente/vazio, quantity <= 0, dimensao <= 0, type fora do enum, nome invalido, weightKg <= 0 ou com mais de 2 casas''', 1),
    ('''- A soma de peso/volume contra a capacidade da carona sera validada na S2-03.''',
     '''- Desde a S2-03 a soma de peso/volume das bagagens CONFIRMED e travada no
  aceite (PATCH abaixo); limites declarados em `cargoDetails.maxWeightKg`
  (carona) e `cargoVolumeLiters` (veiculo).''', 1),
    ('''- `400` decision ausente/fora do enum; REJECT sem reason; reason > 500''',
     '''- `400` decision ausente/fora do enum; REJECT sem reason; reason > 500; peso/volume acima da capacidade (S2-03)''', 1),
    ('''- Aceite/recusa considera apenas vaga de passageiro; a validacao de
  peso/volume da bagagem contra o veiculo fica para a S2-03.''',
     '''- S2-03 (RN03/RN12): o ACCEPT tambem trava capacidade — `409` quando nao
  ha vaga (decisoes simultaneas sao serializadas por lock pessimista na
  carona) e `400` quando a soma de peso das bagagens CONFIRMED excede
  `cargoDetails.maxWeightKg` ou o volume total (L x A x C x quantidade)
  excede o `cargoVolumeLiters` do veiculo. Detalhes em docs/ARQUITETURA.md.''', 1),
])

# ---------------------------------------------------------------------------
# H) scripts/setup-s2-01.sh — não regredir Baggage/DTOs com weightKg
# ---------------------------------------------------------------------------
S201 = "scripts/setup-s2-01.sh"

def guard_s201(write_line, delim, arquivo, rotulo):
    old_open = write_line
    new_open = ('if grep -q "weightKg" "' + arquivo + '" 2>/dev/null; then\n'
                '  log "preservado: ' + rotulo + ' (weightKg da S2-03 presente — não regredir)"\n'
                'else\n' + write_line)
    old_close = "\n" + delim + "\n"
    new_close = "\n" + delim + "\nfi\n"
    return [(old_open, new_open, 1), (old_close, new_close, 1)]

if os.path.isfile(S201):
    s201_src = load(S201)
    if "weightKg da S2-03 presente" in s201_src:
        print(f"  [skip] {S201} (já patcheado)")
    else:
        patches = []
        patches += guard_s201(
            """write_file "$RIDES_MAIN/domain/Baggage.java" <<'@@BAGGAGE_ENTITY@@'""",
            "@@BAGGAGE_ENTITY@@", "$RIDES_MAIN/domain/Baggage.java", "Baggage.java")
        patches += guard_s201(
            """write_file "$RIDES_MAIN/dto/BaggageItemRequest.java" <<'@@BAGGAGE_ITEM_REQUEST@@'""",
            "@@BAGGAGE_ITEM_REQUEST@@", "$RIDES_MAIN/dto/BaggageItemRequest.java", "BaggageItemRequest.java")
        patches += guard_s201(
            """write_file "$RIDES_MAIN/dto/BaggageItemResponse.java" <<'@@BAGGAGE_ITEM_RESPONSE@@'""",
            "@@BAGGAGE_ITEM_RESPONSE@@", "$RIDES_MAIN/dto/BaggageItemResponse.java", "BaggageItemResponse.java")
        patch(S201, "weightKg da S2-03 presente", patches)
else:
    print(f"  [skip] {S201} (ausente — nada a proteger)")

# ---------------------------------------------------------------------------
# I) tests/s2-02/test_s2_02.py — V9 passa a ser esperada
# ---------------------------------------------------------------------------
T202 = "tests/s2-02/test_s2_02.py"
if os.path.isfile(T202):
    patch(T202, '"V9"', [
        ('''        self.assertEqual(["V2", "V7", "V8"], versoes,
                         "S2-02 não pode criar migration nova (CANCELLED reutilizado)")''',
         '''        self.assertEqual(["V2", "V7", "V8", "V9"], versoes,
                         "S2-02 não cria migration (CANCELLED reutilizado); V9 pertence à S2-03")''', 1),
    ])
else:
    print(f"  [skip] {T202} (ausente)")

# ---------------------------------------------------------------------------
# J) tests/s2-01/test_s2_01.py — @Positive agora são 4 (weightKg opcional)
# ---------------------------------------------------------------------------
T201 = "tests/s2-01/test_s2_01.py"
if os.path.isfile(T201):
    patch(T201, "weightKg opcional desde a S2-03", [
        ('''        self.assertEqual(src.count("@Positive"), 3, "3 dimensões > 0")''',
         '''        self.assertIn(src.count("@Positive"), (3, 4),
                      "3 dimensões > 0 (+weightKg opcional desde a S2-03)")''', 1),
    ])
else:
    print(f"  [skip] {T201} (ausente)")

# ---------------------------------------------------------------------------
# K) setup-s2-01.sh e setup-s2-02.sh — não regredir os próprios arquivos de
#    teste quando já compatíveis com a S2-03 (write_file sobrescreveria o
#    patch de compatibilidade a cada re-execução das suítes).
# ---------------------------------------------------------------------------
def proteger_write_test(gerador, marcador, write_line, delim, rotulo):
    if not os.path.isfile(gerador):
        print(f"  [skip] {gerador} (ausente)")
        return
    old_open = write_line
    new_open = ('if grep -qF ' + '"' + marcador + '" ' + rotulo + ' 2>/dev/null; then\n'
                '  log "preservado: ' + rotulo + ' (compat S2-03 presente — não regredir)"\n'
                'else\n' + write_line)
    old_close = "\n" + delim + "\n"
    new_close = "\n" + delim + "\nfi\n"
    patch(gerador, "compat S2-03 presente", [
        (old_open, new_open, 1),
        (old_close, new_close, 1),
    ])

proteger_write_test(
    "scripts/setup-s2-01.sh",
    "weightKg opcional desde a S2-03",
    """write_file "tests/s2-01/test_s2_01.py" <<'@@PYTEST@@'""",
    "@@PYTEST@@",
    "tests/s2-01/test_s2_01.py")

proteger_write_test(
    "scripts/setup-s2-02.sh",
    "V9",
    """write_file "tests/s2-02/test_s2_02.py" <<'@@TESTPY@@'""",
    "@@TESTPY@@",
    "tests/s2-02/test_s2_02.py")

print("[patcher] S2-03: todos os patches aplicados com sucesso.")
@@PYPATCH@@

# =============================================================================
# 5) CapacityLockApiIT — critérios de aceite da issue #10
# =============================================================================
write_file "$APP_IT/CapacityLockApiIT.java" <<'@@CAPIT@@'
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
@@CAPIT@@

# =============================================================================
# 6) tests/s2-03 — suíte de verificação (estrutural + maven opcional)
# =============================================================================
write_file "tests/s2-03/test_s2_03.py" <<'@@TESTPY@@'
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
@@TESTPY@@

write_file "tests/s2-03/run-maven.sh" <<'@@RUNMV@@'
#!/usr/bin/env bash
# =============================================================================
# run-maven.sh — validação REAL da S2-03 (compilação, testes unitários e IT)
# Requer: JDK 17+, Maven e Docker (Testcontainers/Jakarta para as ITs).
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

command -v mvn >/dev/null || { echo "[run-maven] mvn não encontrado." >&2; exit 1; }

echo "[run-maven] 1/2 — testes unitários do raiji-rides (+ deps)..."
mvn -q -pl raiji-rides -am test -Djacoco.skip=true

echo "[run-maven] 2/2 — integração CapacityLockApiIT no raiji-app..."
if docker info >/dev/null 2>&1; then
  mvn -q -pl raiji-app -am verify \
      -Dit.test=CapacityLockApiIT -Dtest=NONE \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Djacoco.skip=true
else
  echo "[run-maven] AVISO: docker indisponível — pulando ITs." >&2
  echo "[run-maven] Rode depois: mvn -pl raiji-app -am verify -Dit.test=CapacityLockApiIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false" >&2
fi

echo "[run-maven] regressão completa (S1-06 + S2-01 + S2-02 + JaCoCo 80%):"
echo "  mvn -q verify   # roda TUDO"
echo "[run-maven] OK"
@@RUNMV@@

write_file "tests/s2-03/run-all.sh" <<'@@RUNALL@@'
#!/usr/bin/env bash
# =============================================================================
# run-all.sh — verificação completa da S2-03
#   1. sintaxe dos scripts
#   2. testes estruturais (python, sem Maven)
#   3. idempotência do gerador (2ª execução não altera nada)
#   4. opcional: --with-maven → compilação + testes reais
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

GRN=$'\033[0;32m'; RED=$'\033[0;31m'; NC=$'\033[0m'
ok()   { printf '%s[PASS]%s %s\n' "$GRN" "$NC" "$*"; }
fail() { printf '%s[FAIL]%s %s\n' "$RED" "$NC" "$*"; exit 1; }

echo "== 1/4 sintaxe dos scripts =="
for s in scripts/setup-s2-03.sh tests/s2-03/run-maven.sh tests/s2-03/run-all.sh; do
  bash -n "$s" || fail "sintaxe: $s"
done
ok "bash -n em 3 scripts"

echo "== 2/4 testes estruturais =="
python3 "$HERE/test_s2_03.py" || fail "testes estruturais"
ok "test_s2_03.py"

echo "== 3/4 idempotência do gerador =="
ALVO=$(find raiji-rides raiji-app docs scripts -type f \
        \( -name '*.java' -o -name '*.sql' -o -name '*.md' -o -name '*.sh' \) \
        | sort | xargs sha256sum | sha256sum)
bash scripts/setup-s2-03.sh >/dev/null 2>&1 || fail "re-execução do gerador"
ALVO2=$(find raiji-rides raiji-app docs scripts -type f \
        \( -name '*.java' -o -name '*.sql' -o -name '*.md' -o -name '*.sh' \) \
        | sort | xargs sha256sum | sha256sum)
[[ "$ALVO" == "$ALVO2" ]] || fail "gerador não idempotente (2ª execução alterou arquivos)"
ok "2ª execução do gerador: árvore idêntica"

echo "== 4/4 validação real (opcional) =="
if [[ "${1:-}" == "--with-maven" ]]; then
  bash "$HERE/run-maven.sh" || fail "run-maven.sh"
  ok "Maven: unitários + CapacityLockApiIT"
else
  echo "  (pulada — use: bash tests/s2-03/run-all.sh --with-maven)"
fi

echo
ok "S2-03 verificada com sucesso."
@@RUNALL@@

chmod +x scripts/setup-s2-03.sh tests/s2-03/run-all.sh tests/s2-03/run-maven.sh

log "implementação da S2-03 concluída."
cat <<RESUMO
  Novos:  V9__add_baggage_weight.sql (weight_kg opcional),
          docs/ARQUITETURA.md (lock pessimista + @Version),
          CapacityLockApiIT (concorrência ExecutorService + limites),
          tests/s2-03/
  Atual.: Baggage/BaggageItemRequest/Response (weightKg),
          RideRepository (findByIdForUpdate FOR UPDATE),
          RidePassengerService (lock + validarCapacidadeCarga no ACCEPT),
          RidePassengerServiceTest (+7 testes), RideRequestControllerTest,
          docs/API.md, guards no setup-s2-01.sh, V9 no test_s2_02.py
  Critérios: sem vaga => 409; peso > maxWeightKg => 400; volume >
             cargoVolumeLiters => 400; corrida => 1× 200 + 1× 409;
             limite exato passa, 1 acima reprova; rollback total na falha.
  Verificar:  bash tests/s2-03/run-all.sh              (estrutural)
              bash tests/s2-03/run-all.sh --with-maven (real: mvn + docker)
RESUMO

if [[ "$MODE" == "check" ]]; then
  exec bash tests/s2-03/run-all.sh
fi
