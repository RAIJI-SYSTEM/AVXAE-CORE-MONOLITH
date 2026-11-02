#!/usr/bin/env bash
# =============================================================================
# setup-s2-02.sh — S2-02 · Aceite/recusa de solicitação com bagagem (RF08/RF19)
# =============================================================================
# Script ÚNICO e autocontido que implementa toda a issue #9 no projeto:
#
#   NOVOS:
#   * raiji-rides/.../domain/RideDecision.java        (enum ACCEPT|REJECT)
#   * raiji-rides/.../dto/RideDecisionRequest.java    (record + @AssertTrue)
#   * raiji-rides/.../dto/RideDecisionResponse.java   (record {id,status,confirmedAt})
#   * raiji-rides/.../event/RideDecisionEvent.java    (evento de decisão)
#   * raiji-rides/.../web/RidePassengerController.java(PATCH /{requestId})
#   * raiji-app/.../it/RideDecisionApiIT.java         (critérios de aceite)
#   * tests/s2-02/*                                   (suíte de verificação)
#
#   ATUALIZADOS:
#   * RidePassengerService -> decide() (dono, PENDING, vagas, motivo, evento)
#   * NotificationService  -> handleRideDecision() (notifica o passageiro)
#   * RidePassengerServiceTest -> 7 testes unitários do decide()
#   * docs/API.md          -> contrato PATCH documentado
#   * scripts/setup-s2-01.sh -> proteção: não regrava o service se o
#                               decide() da S2-02 já estiver aplicado
#
#   SEM migration: REJECT reutiliza o status CANCELLED (decisão da issue).
#
# Uso (a partir da RAIZ DO PROJETO):
#   bash setup-s2-02.sh           # implementa tudo (idempotente)
#   bash setup-s2-02.sh --check   # implementa e roda a verificação
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
  echo "[setup-s2-02] ERRO: raiz do projeto não encontrada (pom.xml + docker-compose.yml)." >&2
  exit 1
fi
cd "$ROOT"
echo "[setup-s2-02] raiz do projeto: $ROOT"

SELF="$(readlink -f "${BASH_SOURCE[0]}")"
CANONICAL="$ROOT/scripts/setup-s2-02.sh"
mkdir -p scripts
if [[ "$SELF" != "$CANONICAL" ]]; then
  cp "$SELF" "$CANONICAL"
  echo "[setup-s2-02] gerador instalado em scripts/setup-s2-02.sh (local canônico)"
fi

GRN=$'\033[0;32m'; NC=$'\033[0m'
log() { printf '%s[setup-s2-02]%s %s\n' "$GRN" "$NC" "$*"; }

write_file() {
  mkdir -p "$(dirname "$1")"
  cat > "$1"
  log "gravado: $1"
}

RIDES_MAIN="raiji-rides/src/main/java/com/raiji/rides"
APP_IT="raiji-app/src/test/java/com/raiji/it"

# =============================================================================
# 1) RideDecision — enum ACCEPT|REJECT (contrato da issue, qualquer caixa)
# =============================================================================
write_file "$RIDES_MAIN/domain/RideDecision.java" <<'@@RIDE_DECISION@@'
package com.raiji.rides.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * S2-02 / RF08 — decisão do motorista sobre uma solicitação de vaga.
 *
 * <p>O contrato da API usa maiúsculas ({@code ACCEPT}, {@code REJECT});
 * a entrada aceita qualquer caixa. Valor fora do enum na requisição
 * => erro de desserialização => 400.
 */
public enum RideDecision {

    ACCEPT,
    REJECT;

    @JsonCreator
    public static RideDecision from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (RideDecision decision : values()) {
            if (decision.name().equalsIgnoreCase(value.trim())) {
                return decision;
            }
        }
        throw new IllegalArgumentException(
                "Decisão inválida: '" + value
                        + "'. Valores aceitos: ACCEPT, REJECT."
        );
    }
}
@@RIDE_DECISION@@

# =============================================================================
# 2) RideDecisionRequest — payload do PATCH (reason obrigatório no REJECT)
# =============================================================================
write_file "$RIDES_MAIN/dto/RideDecisionRequest.java" <<'@@DECISION_REQUEST@@'
package com.raiji.rides.dto;

import com.raiji.rides.domain.RideDecision;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * S2-02 / RF08 — payload do PATCH /api/rides/{rideId}/requests/{requestId}.
 *
 * <p>{@code reason} é obrigatório quando {@code decision = REJECT}
 * (validação cross-field com @AssertTrue, mesmo padrão do
 * UpdateProfileRequest.hasAtLeastOneField()).
 */
public record RideDecisionRequest(

        @NotNull(message = "A decisão é obrigatória (ACCEPT ou REJECT).")
        RideDecision decision,

        @Size(max = 500, message = "O motivo deve ter no máximo 500 caracteres.")
        String reason
) {

    @AssertTrue(message = "O motivo (reason) é obrigatório ao recusar (REJECT).")
    public boolean hasReasonWhenReject() {
        return decision != RideDecision.REJECT
                || (reason != null && !reason.isBlank());
    }
}
@@DECISION_REQUEST@@

# =============================================================================
# 3) RideDecisionResponse — {id, status, confirmedAt} (contrato da issue)
# =============================================================================
write_file "$RIDES_MAIN/dto/RideDecisionResponse.java" <<'@@DECISION_RESPONSE@@'
package com.raiji.rides.dto;

import java.time.Instant;
import java.util.UUID;

import com.raiji.common.enums.RidePassengerStatus;
import com.raiji.rides.domain.RidePassenger;

/**
 * S2-02 / RF08 — resposta da decisão: {@code {id, status, confirmedAt}}.
 *
 * <p>ACCEPT => {@code CONFIRMED} + {@code confirmedAt} preenchido.
 * REJECT => {@code CANCELLED} + {@code confirmedAt} nulo (status CANCELLED
 * é reutilizado; não há valor REJECTED nem migration nova — decisão da issue).
 */
public record RideDecisionResponse(
        UUID id,
        RidePassengerStatus status,
        Instant confirmedAt
) {

    public static RideDecisionResponse from(RidePassenger passenger) {
        return new RideDecisionResponse(
                passenger.getId(),
                passenger.getStatus(),
                passenger.getConfirmedAt()
        );
    }
}
@@DECISION_RESPONSE@@

# =============================================================================
# 4) RideDecisionEvent — consumido pelo NotificationService (RF19)
# =============================================================================
write_file "$RIDES_MAIN/event/RideDecisionEvent.java" <<'@@DECISION_EVENT@@'
package com.raiji.rides.event;

import java.time.Instant;
import java.util.UUID;

import com.raiji.rides.domain.RideDecision;

/**
 * S2-02 / RF08+RF19 — publicado quando o motorista decide (ACCEPT/REJECT)
 * uma solicitação. Consumido pelo NotificationService (S1-06) para
 * notificar o passageiro.
 */
public record RideDecisionEvent(
        UUID rideId,
        UUID requestId,
        String driverSub,
        String driverName,
        String passengerSub,
        String passengerName,
        RideDecision decision,
        String reason,
        Instant decidedAt
) {
}
@@DECISION_EVENT@@

# =============================================================================
# 5) RidePassengerController — PATCH /api/rides/{rideId}/requests/{requestId}
# =============================================================================
write_file "$RIDES_MAIN/web/RidePassengerController.java" <<'@@PASSENGER_CONTROLLER@@'
package com.raiji.rides.web;

import java.util.UUID;

import com.raiji.rides.dto.RideDecisionRequest;
import com.raiji.rides.dto.RideDecisionResponse;
import com.raiji.rides.service.RidePassengerService;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * S2-02 / RF08+RF19 — aceite/recusa do motorista sobre as solicitações,
 * decididos APÓS visualizar a bagagem declarada (GET da S1-06/S2-01).
 *
 * <p>Compartilha o base path com o RideRequestController (POST/GET);
 * o PATCH /{requestId} é exclusivo deste controller — sem colisão de rota.
 */
@RestController
@RequestMapping("/api/rides/{rideId}/requests")
public class RidePassengerController {

    private final RidePassengerService ridePassengerService;

    public RidePassengerController(
            RidePassengerService ridePassengerService
    ) {
        this.ridePassengerService = ridePassengerService;
    }

    /** 200 => {id, status, confirmedAt}. reason obrigatório no REJECT. */
    @PatchMapping("/{requestId}")
    @PreAuthorize("hasRole('DRIVER')")
    public RideDecisionResponse decide(
            @PathVariable UUID rideId,
            @PathVariable UUID requestId,
            @Valid @RequestBody RideDecisionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ridePassengerService.decide(
                rideId,
                requestId,
                jwt.getSubject(),
                request.decision(),
                request.reason()
        );
    }
}
@@PASSENGER_CONTROLLER@@

# =============================================================================
# 6) PATCHes nos arquivos existentes (python — âncoras exatas + guards)
# =============================================================================
python3 - <<'@@PYPATCH@@'
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

# ---------------------------------------------------------------------------
# A) RidePassengerService — decide() (RF08)
# ---------------------------------------------------------------------------
SVC = "raiji-rides/src/main/java/com/raiji/rides/service/RidePassengerService.java"

decide = '''
    /**
     * S2-02 / RF08: o motorista aceita (ACCEPT) ou recusa (REJECT) a
     * solicitação após visualizar a bagagem declarada (S2-01).
     *
     * <p>ACCEPT => CONFIRMED + confirmedAt + decremento de availableSeats.
     * REJECT => CANCELLED + cancellationReason (vagas permanecem intactas).
     * Nos dois casos o passageiro é notificado via RideDecisionEvent (RF19).
     */
    @Transactional
    public RideDecisionResponse decide(
            UUID rideId,
            UUID requestId,
            String driverSub,
            RideDecision decision,
            String reason
    ) {
        if (decision == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A decisão é obrigatória (ACCEPT ou REJECT)."
            );
        }

        if (decision == RideDecision.REJECT
                && (reason == null || reason.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O motivo (reason) é obrigatório ao recusar (REJECT)."
            );
        }

        Ride ride = rideRepository.findById(rideId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Carona não encontrada."
                ));

        if (!ride.getDriverSub().equals(driverSub)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não tem permissão para decidir solicitações desta carona."
            );
        }

        RidePassenger passenger = ridePassengerRepository.findById(requestId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> rideId.equals(p.getRideId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Solicitação não encontrada."
                ));

        if (passenger.getStatus() != RidePassengerStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta solicitação já foi decidida."
            );
        }

        Instant decidedAt;
        if (decision == RideDecision.ACCEPT) {
            if (ride.getAvailableSeats() == null
                    || ride.getAvailableSeats() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Não há vagas disponíveis nesta carona."
                );
            }
            ride.setAvailableSeats(ride.getAvailableSeats() - 1);
            passenger.confirm();
            decidedAt = passenger.getConfirmedAt();
        } else {
            passenger.cancel(reason.trim());
            decidedAt = Instant.now();
        }

        rideRepository.save(ride);
        RidePassenger saved = ridePassengerRepository.save(passenger);

        eventPublisher.publishEvent(
                new RideDecisionEvent(
                        ride.getId(),
                        saved.getId(),
                        ride.getDriverSub(),
                        ride.getDriverName(),
                        saved.getPassengerSub(),
                        saved.getPassengerName(),
                        decision,
                        decision == RideDecision.REJECT ? reason.trim() : null,
                        decidedAt
                )
        );

        return RideDecisionResponse.from(saved);
    }
'''

tail_old = '''        return ridePassengerRepository
                .findByRideIdAndDeletedAtIsNull(rideId)
                .stream()
                .map(RidePassengerResponse::from)
                .toList();
    }
}'''

tail_new = '''        return ridePassengerRepository
                .findByRideIdAndDeletedAtIsNull(rideId)
                .stream()
                .map(RidePassengerResponse::from)
                .toList();
    }
''' + decide + '''}'''

patch(SVC, "public RideDecisionResponse decide(", [
    ('''import java.time.LocalDateTime;''',
     '''import java.time.Instant;
import java.time.LocalDateTime;''', 1),
    ('''import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;''',
     '''import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.domain.RidePassenger;''', 1),
    ('''import com.raiji.rides.dto.BaggageItemRequest;
import com.raiji.rides.dto.RidePassengerResponse;''',
     '''import com.raiji.rides.dto.BaggageItemRequest;
import com.raiji.rides.dto.RideDecisionResponse;
import com.raiji.rides.dto.RidePassengerResponse;''', 1),
    ('''import com.raiji.rides.event.RideRequestCreatedEvent;''',
     '''import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.event.RideRequestCreatedEvent;''', 1),
    (tail_old, tail_new, 1),
])

# ---------------------------------------------------------------------------
# B) NotificationService — handleRideDecision (RF19, reusa o padrão S1-06)
# ---------------------------------------------------------------------------
NOTIF = "raiji-rides/src/main/java/com/raiji/rides/service/NotificationService.java"

handle_decision = '''
    /**
     * S2-02 / RF19: notifica o passageiro sobre a decisão do motorista
     * (aceite ou recusa com o motivo declarado).
     */
    @EventListener
    @Transactional
    public void handleRideDecision(RideDecisionEvent event) {

        String message = event.decision() == RideDecision.ACCEPT
                ? "Sua solicitação de carona com " + event.driverName()
                        + " foi ACEITA."
                : "Sua solicitação de carona com " + event.driverName()
                        + " foi RECUSADA. Motivo: " + event.reason();

        // coluna message VARCHAR(500) — defesa contra motivos longos
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }

        Notification notification = new Notification();
        notification.setRecipientSub(event.passengerSub());
        notification.setRideId(event.rideId());
        notification.setMessage(message);

        notificationRepository.save(notification);

        log.info(
                "Notificação de decisão ({}) criada para o passageiro {} na carona {}",
                event.decision(),
                event.passengerSub(),
                event.rideId()
        );
    }
'''

notif_tail_old = '''        log.info(
                "Notificação criada para o motorista {} referente à carona {}",
                event.driverSub(),
                event.rideId()
        );
    }
}'''

notif_tail_new = '''        log.info(
                "Notificação criada para o motorista {} referente à carona {}",
                event.driverSub(),
                event.rideId()
        );
    }
''' + handle_decision + '''}'''

patch(NOTIF, "handleRideDecision", [
    ('''import com.raiji.rides.domain.Notification;
import com.raiji.rides.event.RideRequestCreatedEvent;''',
     '''import com.raiji.rides.domain.Notification;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.event.RideRequestCreatedEvent;''', 1),
    (notif_tail_old, notif_tail_new, 1),
])

# ---------------------------------------------------------------------------
# C) RidePassengerServiceTest — testes unitários do decide()
# ---------------------------------------------------------------------------
SVCTEST = "raiji-rides/src/test/java/com/raiji/rides/service/RidePassengerServiceTest.java"

decision_tests = '''
    // =========================================================================
    // S2-02 / RF08 — decide(): aceite/recusa do motorista
    // =========================================================================

    private RidePassenger solicitacaoPendente() {
        RidePassenger passenger = new RidePassenger();
        ReflectionTestUtils.setField(passenger, "id", UUID.randomUUID());
        passenger.setRideId(rideId);
        passenger.setPassengerSub(PASSENGER_SUB);
        passenger.setPassengerName("João");
        // status nasce PENDING na entidade — não existe setStatus();
        // transições apenas via confirm()/cancel()

        when(passengerRepository.findById(passenger.getId()))
                .thenReturn(Optional.of(passenger));

        return passenger;
    }

    @Test
    void deveConfirmarSolicitacaoNoAccept() {
        RidePassenger pendente = solicitacaoPendente();

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB, RideDecision.ACCEPT, null);

        assertEquals(pendente.getId(), response.id());
        assertEquals(RidePassengerStatus.CONFIRMED, response.status());
        assertNotNull(response.confirmedAt());

        ArgumentCaptor<RidePassenger> captor =
                ArgumentCaptor.forClass(RidePassenger.class);
        verify(passengerRepository).save(captor.capture());
        assertEquals(RidePassengerStatus.CONFIRMED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getConfirmedAt());

        // vagas decrementadas: fixture começa com availableSeats = 4
        assertEquals(3, ride.getAvailableSeats());
        verify(rideRepository).save(ride);
        verify(eventPublisher).publishEvent(any(RideDecisionEvent.class));
    }

    @Test
    void deveCancelarComMotivoNoReject() {
        RidePassenger pendente = solicitacaoPendente();

        RideDecisionResponse response = service.decide(
                rideId, pendente.getId(), DRIVER_SUB,
                RideDecision.REJECT, "Bagagem excede o porta-malas.");

        assertEquals(RidePassengerStatus.CANCELLED, response.status());
        assertNull(response.confirmedAt());

        ArgumentCaptor<RidePassenger> captor =
                ArgumentCaptor.forClass(RidePassenger.class);
        verify(passengerRepository).save(captor.capture());
        assertEquals(RidePassengerStatus.CANCELLED, captor.getValue().getStatus());
        assertEquals(
                "Bagagem excede o porta-malas.",
                captor.getValue().getCancellationReason());

        // vagas permanecem intactas no REJECT
        assertEquals(4, ride.getAvailableSeats());
        verify(eventPublisher).publishEvent(any(RideDecisionEvent.class));
    }

    @Test
    void deveRejeitarRejectSemMotivo() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, UUID.randomUUID(), DRIVER_SUB,
                        RideDecision.REJECT, "   ")
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarDecisaoPorOutroMotorista() {
        RidePassenger pendente = solicitacaoPendente();

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), "outro-driver",
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarSolicitacaoJaDecidida() {
        RidePassenger pendente = solicitacaoPendente();
        pendente.confirm();

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.REJECT, "Qualquer motivo")
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarAcceptSemVagaDisponivel() {
        RidePassenger pendente = solicitacaoPendente();
        ride.setAvailableSeats(0);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, pendente.getId(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }

    @Test
    void deveRejeitarSolicitacaoInexistenteNaDecisao() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.decide(rideId, UUID.randomUUID(), DRIVER_SUB,
                        RideDecision.ACCEPT, null)
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(passengerRepository, never()).save(any());
    }
'''

test_tail_old = '''        assertEquals(1, responses.size());
        assertEquals("João", responses.get(0).passengerName());
    }
}'''

test_tail_new = '''        assertEquals(1, responses.size());
        assertEquals("João", responses.get(0).passengerName());
    }
''' + decision_tests + '''}'''

patch(SVCTEST, "deveConfirmarSolicitacaoNoAccept", [
    ('''import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RidePassenger;''',
     '''import com.raiji.rides.domain.Ride;
import com.raiji.rides.domain.RideDecision;
import com.raiji.rides.domain.RidePassenger;''', 1),
    ('''import com.raiji.rides.dto.BaggageItemRequest;
import com.raiji.rides.dto.RidePassengerResponse;''',
     '''import com.raiji.rides.dto.BaggageItemRequest;
import com.raiji.rides.dto.RideDecisionResponse;
import com.raiji.rides.dto.RidePassengerResponse;''', 1),
    ('''import com.raiji.rides.event.RideRequestCreatedEvent;''',
     '''import com.raiji.rides.event.RideDecisionEvent;
import com.raiji.rides.event.RideRequestCreatedEvent;''', 1),
    (test_tail_old, test_tail_new, 1),
])

# Reparo: árvores que já receberam a 1ª versão destes testes usavam
# passenger.setStatus(...), setter que NÃO existe na entidade RidePassenger
# (o status nasce PENDING; transições só via confirm()/cancel()).
_src = load(SVCTEST)
_broken = '''        passenger.setStatus(RidePassengerStatus.PENDING);
'''
if _broken in _src:
    save(SVCTEST, _src.replace(_broken, ''))
    print(f"  [fix]  {SVCTEST} (removido passenger.setStatus — setter inexistente)")

# ---------------------------------------------------------------------------
# D) docs/API.md — contrato do PATCH (estilo ASCII do documento)
# ---------------------------------------------------------------------------
DOCS = "docs/API.md"

docs_old = '''- A soma de peso/volume contra a capacidade da carona sera validada na S2-03.
'''

docs_new = docs_old + r'''
---

## Decisao do motorista (S2-02)

### PATCH /api/rides/{rideId}/requests/{requestId}

Autenticacao: Bearer token com role `DRIVER` (dono da carona).

O motorista decide apos ver a bagagem declarada (GET da S1-06/S2-01).
`ACCEPT` confirma o passageiro e decrementa `availableSeats`; `REJECT`
cancela a solicitacao com status `CANCELLED` (reutilizado — sem novo valor
de enum nem migration) e EXIGE `reason`. O passageiro e notificado nos
dois casos (NotificationService, S1-06).

Body (JSON):

```json
{ "decision": "ACCEPT" }
```

```json
{ "decision": "REJECT", "reason": "A bagagem excede a capacidade do veiculo." }
```

| Campo | Tipo | Obrigatorio | Regras |
|---|---|---:|---|
| decision | string | sim | `ACCEPT` \| `REJECT` (qualquer caixa na entrada) |
| reason | string | REJECT | 1..500 caracteres; obrigatorio quando REJECT |

Resposta `200`:

```json
{ "id": "…", "status": "CONFIRMED", "confirmedAt": "…" }
```

No REJECT: `{ "id": "…", "status": "CANCELLED", "confirmedAt": null }`.

Erros:

- `400` decision ausente/fora do enum; REJECT sem reason; reason > 500
- `401` sem token · `403` sem role DRIVER ou motorista nao dono da carona
- `404` carona ou solicitacao inexistente (ou de outra carona)
- `409` solicitacao ja decidida (status != PENDING); ACCEPT sem vagas

Observacoes:

- Aceite/recusa considera apenas vaga de passageiro; a validacao de
  peso/volume da bagagem contra o veiculo fica para a S2-03.
'''

patch(DOCS, "Decisao do motorista (S2-02)", [
    (docs_old, docs_new, 1),
])

print("[patcher] S2-02: service, notificação, testes unitários e docs patcheados.")

# ---------------------------------------------------------------------------
# E) scripts/setup-s2-01.sh — proteção de composição entre geradores
#    O gerador da S2-01 sobrescreve RidePassengerService.java inteiro
#    (write_file). Se o decide() da S2-02 já estiver aplicado, a regravação
#    é pulada — assim rodar a suíte S2-01 depois da S2-02 não apaga nada.
# ---------------------------------------------------------------------------
import os

S201 = "scripts/setup-s2-01.sh"

old_open = """write_file "$RIDES_MAIN/service/RidePassengerService.java" <<'@@SERVICE@@'"""
new_open = """if grep -q "public RideDecisionResponse decide(" "$RIDES_MAIN/service/RidePassengerService.java" 2>/dev/null; then
  log "preservado: RidePassengerService.java (decide() da S2-02 presente — não regredir)"
else
write_file "$RIDES_MAIN/service/RidePassengerService.java" <<'@@SERVICE@@'"""

old_close = "\n@@SERVICE@@\n"
new_close = "\n@@SERVICE@@\nfi\n"

if os.path.isfile(S201):
    patch(S201, "não regredir", [
        (old_open, new_open, 1),
        (old_close, new_close, 1),
    ])
else:
    print(f"  [skip] {S201} (ausente — nada a proteger)")

print("[patcher] S2-02: todos os patches aplicados com sucesso.")
@@PYPATCH@@

# =============================================================================
# 7) RideDecisionApiIT — critérios de aceite da issue (RF08/RF19)
# =============================================================================
write_file "$APP_IT/RideDecisionApiIT.java" <<'@@DECISION_IT@@'
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
@@DECISION_IT@@

# =============================================================================
# 8) tests/s2-02 — suíte de verificação (estrutural + maven opcional)
# =============================================================================
if grep -qF "V9" tests/s2-02/test_s2_02.py 2>/dev/null; then
  log "preservado: tests/s2-02/test_s2_02.py (compat S2-03 presente — não regredir)"
else
write_file "tests/s2-02/test_s2_02.py" <<'@@TESTPY@@'
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
        self.assertEqual(["V2", "V7", "V8"], versoes,
                         "S2-02 não pode criar migration nova (CANCELLED reutilizado)")

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
@@TESTPY@@
fi

write_file "tests/s2-02/run-maven.sh" <<'@@RUNMV@@'
#!/usr/bin/env bash
# =============================================================================
# run-maven.sh — validação REAL da S2-02 (compilação, testes unitários e IT)
# Requer: JDK 17+, Maven e Docker (Testcontainers/Jakarta para as ITs).
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

command -v mvn >/dev/null || { echo "[run-maven] mvn não encontrado." >&2; exit 1; }

echo "[run-maven] 1/2 — testes unitários do raiji-rides (+ deps)..."
mvn -q -pl raiji-rides -am test -Djacoco.skip=true

echo "[run-maven] 2/2 — integração RideDecisionApiIT no raiji-app..."
if docker info >/dev/null 2>&1; then
  mvn -q -pl raiji-app -am verify \
      -Dit.test=RideDecisionApiIT -Dtest=NONE \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Djacoco.skip=true
else
  echo "[run-maven] AVISO: docker indisponível — pulando ITs." >&2
  echo "[run-maven] Rode depois: mvn -pl raiji-app -am verify -Dit.test=RideDecisionApiIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false" >&2
fi

echo "[run-maven] regressão completa (S1-06 + S2-01 + JaCoCo 80%):"
echo "  mvn -q verify   # roda TUDO"
echo "[run-maven] OK"
@@RUNMV@@

write_file "tests/s2-02/run-all.sh" <<'@@RUNALL@@'
#!/usr/bin/env bash
# =============================================================================
# run-all.sh — verificação completa da S2-02
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
for s in scripts/setup-s2-02.sh tests/s2-02/run-maven.sh tests/s2-02/run-all.sh; do
  bash -n "$s" || fail "sintaxe: $s"
done
ok "bash -n em 3 scripts"

echo "== 2/4 testes estruturais =="
python3 "$HERE/test_s2_02.py" || fail "testes estruturais"
ok "test_s2_02.py"

echo "== 3/4 idempotência do gerador =="
ALVO=$(find raiji-rides raiji-app docs scripts -type f \
        \( -name '*.java' -o -name '*.sql' -o -name '*.md' -o -name '*.sh' \) \
        | sort | xargs sha256sum | sha256sum)
bash scripts/setup-s2-02.sh >/dev/null 2>&1 || fail "re-execução do gerador"
ALVO2=$(find raiji-rides raiji-app docs scripts -type f \
        \( -name '*.java' -o -name '*.sql' -o -name '*.md' -o -name '*.sh' \) \
        | sort | xargs sha256sum | sha256sum)
[[ "$ALVO" == "$ALVO2" ]] || fail "gerador não idempotente (2ª execução alterou arquivos)"
ok "2ª execução do gerador: árvore idêntica"

echo "== 4/4 validação real (opcional) =="
if [[ "${1:-}" == "--with-maven" ]]; then
  bash "$HERE/run-maven.sh" || fail "run-maven.sh"
  ok "Maven: unitários + RideDecisionApiIT"
else
  echo "  (pulada — use: bash tests/s2-02/run-all.sh --with-maven)"
fi

echo
ok "S2-02 verificada com sucesso."
@@RUNALL@@

chmod +x scripts/setup-s2-02.sh tests/s2-02/run-all.sh tests/s2-02/run-maven.sh

log "implementação da S2-02 concluída."
cat <<RESUMO
  Novos:  RideDecision (domain), RideDecisionRequest/Response (dto),
          RideDecisionEvent (event), RidePassengerController (web),
          RideDecisionApiIT, tests/s2-02/
  Atual.: RidePassengerService (decide), NotificationService
          (handleRideDecision), RidePassengerServiceTest (+7 testes),
          docs/API.md
  Sem migration: REJECT reutiliza CANCELLED (decisão da issue #9).
  Verificar:  bash tests/s2-02/run-all.sh              (estrutural)
              bash tests/s2-02/run-all.sh --with-maven (real: mvn + docker)
RESUMO

if [[ "$MODE" == "check" ]]; then
  exec bash tests/s2-02/run-all.sh
fi
