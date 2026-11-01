#!/usr/bin/env bash
# =============================================================================
# test_smoke_functional.sh — valida o COMPORTAMENTO do scripts/smoke-test.sh
# contra um servidor mock (Kong+App+Keycloak simulados):
#   cenário 1: tudo saudável, modo serial    -> exit 0, 4 PASS
#   cenário 2: tudo saudável, modo paralelo  -> exit 0
#   cenário 3: --check health isolado        -> exit 0
#   cenário 4: senha errada                  -> exit 1, token/me FAIL, health PASS
#   cenário 5: health DOWN                   -> exit 1
#   cenário 6: servidor fora do ar           -> exit 1 (retries=1 p/ ser rápido)
# =============================================================================
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PORT="${MOCK_PORT:-18080}"
STATE_DIR="$(mktemp -d)"
export MOCK_PORT="$PORT" MOCK_STATE_DIR="$STATE_DIR"

PASS=0; FAIL=0
t_ok() { echo "  ✅ $*"; PASS=$((PASS+1)); }
t_ko() { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
assert_exit() { # esperado cmd...
  local want="$1"; shift
  "$@" >/dev/null 2>&1
  local got=$?
  [[ "$got" == "$want" ]]
}

cleanup() {
  [[ -n "${SERVER_PID:-}" ]] && kill "$SERVER_PID" 2>/dev/null
  rm -rf "$STATE_DIR"
}
trap cleanup EXIT

echo "== subindo mock server na porta $PORT =="
python3 tests/cicd/mock_server.py & SERVER_PID=$!
for _ in $(seq 1 50); do
  python3 - "http://127.0.0.1:$PORT/actuator/health" <<'PY' && break
import sys, urllib.request
try: urllib.request.urlopen(sys.argv[1], timeout=1)
except Exception: sys.exit(1)
PY
  sleep 0.2
done

SMOKE=(./scripts/smoke-test.sh --base "http://127.0.0.1:$PORT" --retries 1 --retry-delay 0)
SMOKE_ENV=(env SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123)

echo "== cenário 1: saudável, serial =="
if assert_exit 0 "${SMOKE_ENV[@]}" "${SMOKE[@]}" --serial; then t_ok "todas as checagens passam (serial)"; else t_ko "deveria passar (serial)"; fi
OUT="$("${SMOKE_ENV[@]}" "${SMOKE[@]}" --serial 2>&1)"
for c in health realm token me; do
  if grep -Eq "\[PASS\].*\b$c\b" <<<"$OUT"; then t_ok "check $c reportado"; else t_ko "check $c não reportado"; fi
done

echo "== cenário 2: saudável, paralelo =="
if assert_exit 0 "${SMOKE_ENV[@]}" "${SMOKE[@]}" --parallel; then t_ok "todas as checagens passam (paralelo)"; else t_ko "deveria passar (paralelo)"; fi

echo "== cenário 3: --check isolado =="
for c in health realm token me; do
  if assert_exit 0 "${SMOKE_ENV[@]}" "${SMOKE[@]}" --check "$c"; then t_ok "--check $c ok"; else t_ko "--check $c deveria passar"; fi
done

echo "== cenário 4: credenciais inválidas (token 401) =="
env SMOKE_USERNAME=teste SMOKE_PASSWORD=errada "${SMOKE[@]}" --serial >/dev/null 2>&1
[[ $? -eq 1 ]] && t_ok "exit=1 com senha errada" || t_ko "deveria falhar com senha errada"
OUT="$(env SMOKE_USERNAME=teste SMOKE_PASSWORD=errada "${SMOKE[@]}" --serial 2>&1)"
grep -Eq "\[PASS\].*\bhealth\b" <<<"$OUT" && t_ok "health continua PASS (falha parcial detectada)" || t_ko "health deveria passar"
grep -Eq "\[FAIL\].*\btoken\b" <<<"$OUT" && t_ok "token FAIL reportado" || t_ko "token deveria falhar"
grep -Eq "\[FAIL\].*\bme\b" <<<"$OUT" && t_ok "me FAIL reportado (depende do token)" || t_ko "me deveria falhar"

echo "== cenário 5: health DOWN =="
echo DOWN > "$STATE_DIR/health.status"
env SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123 "${SMOKE[@]}" --check health >/dev/null 2>&1
[[ $? -eq 1 ]] && t_ok "exit=1 com status DOWN" || t_ko "deveria falhar com DOWN"
echo UP > "$STATE_DIR/health.status"

echo "== cenário 6: servidor fora do ar =="
env SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123 \
  ./scripts/smoke-test.sh --base "http://127.0.0.1:$((PORT+1))" --retries 1 --retry-delay 0 >/dev/null 2>&1
[[ $? -eq 1 ]] && t_ok "exit=1 com servidor indisponível" || t_ko "deveria falhar sem servidor"

echo "== cenário 7: ramo HTTP via curl (shim) =="
SHIM_DIR="$ROOT/tests/cicd/curl-shim"
chmod +x "$SHIM_DIR/curl" 2>/dev/null
if [[ -x "$SHIM_DIR/curl" ]]; then
  ( export PATH="$SHIM_DIR:$PATH"
    command -v curl >/dev/null && "${SMOKE_ENV[@]}" "${SMOKE[@]}" --parallel ) >/dev/null 2>&1
  [[ $? -eq 0 ]] && t_ok "modo curl: todas as checagens passam" || t_ko "modo curl: deveria passar"
  ( export PATH="$SHIM_DIR:$PATH"
    env SMOKE_USERNAME=teste SMOKE_PASSWORD=errada "${SMOKE[@]}" --serial ) >/dev/null 2>&1
  [[ $? -eq 1 ]] && t_ok "modo curl: exit=1 com senha errada" || t_ko "modo curl: deveria falhar com senha errada"
else
  echo "  (shim de curl indisponível — pulado)"
fi

GUARDIAN_ENV=(env ENVIRONMENT=dev "BASE_URL=http://127.0.0.1:$PORT"
              SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123
              GUARDIAN_RETRY=1 GUARDIAN_DELAY=0
              GUARDIAN_RECHECK_DELAY=1
              GUARDIAN_RETRY_RECHECK=1 GUARDIAN_DELAY_RECHECK=0)

echo "== cenário 8: guardian — ambiente saudável =="
echo UP > "$STATE_DIR/health.status"
OUT="$("${GUARDIAN_ENV[@]}" ./scripts/aws/guardian.sh 2>&1)"
if [[ $? -eq 0 ]] && grep -q "saudável" <<<"$OUT"; then
  t_ok "guardian exit=0 com ambiente saudável"
else
  t_ko "guardian deveria sair 0 (saudável): $OUT"
fi

echo "== cenário 9: guardian — falha transitória (recupera na reconfirmação) =="
echo DOWN > "$STATE_DIR/health.status"
( sleep 2; echo UP > "$STATE_DIR/health.status" ) & TOGGLER=$!
OUT="$(env ENVIRONMENT=dev "BASE_URL=http://127.0.0.1:$PORT" \
      SMOKE_USERNAME=teste SMOKE_PASSWORD=teste123 \
      GUARDIAN_RETRY=1 GUARDIAN_DELAY=0 GUARDIAN_RECHECK_DELAY=3 \
      GUARDIAN_RETRY_RECHECK=10 GUARDIAN_DELAY_RECHECK=1 \
      ./scripts/aws/guardian.sh 2>&1)"
RC=$?
wait "$TOGGLER" 2>/dev/null
if [[ $RC -eq 0 ]] && grep -q "transitória" <<<"$OUT"; then
  t_ok "guardian exit=0 detectando falha transitória na reconfirmação"
else
  t_ko "guardian deveria recuperar na reconfirmação (rc=$RC): $OUT"
fi

echo "== cenário 10: guardian — falha confirmada SEM versão anterior =="
echo DOWN > "$STATE_DIR/health.status"
OUT="$("${GUARDIAN_ENV[@]}" ./scripts/aws/guardian.sh 2>&1)"
RC=$?
echo UP > "$STATE_DIR/health.status"
if [[ $RC -eq 1 ]] && grep -q "SEM versão anterior" <<<"$OUT"; then
  t_ok "guardian exit=1 + alerta 'SEM versão anterior' (rollback só com histórico)"
else
  t_ko "guardian deveria falhar sem versão anterior (rc=$RC): $OUT"
fi

echo
echo "resultado: $PASS passaram, $FAIL falharam"
[[ "$FAIL" -eq 0 ]]
