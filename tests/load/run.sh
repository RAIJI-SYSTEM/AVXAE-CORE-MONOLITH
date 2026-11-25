#!/usr/bin/env bash
# =============================================================================
# Testes de carga — Monólito (AVXAE-CORE-MONOLITH)
#
#   ./run.sh deps       instala o Locust no .venv local
#   ./run.sh check      confere Kong/Keycloak/roles/dados antes de rodar
#   ./run.sh seed       cria os usuários de carga no Keycloak (users.csv)
#   ./run.sh smoke      1200 usuários, 1 min (validação rápida de duração)
#   ./run.sh load       1200 usuários, rampa de 60 s + 6 min de platô
#   ./run.sh stress     degraus de 50 em 50 até 800
#   ./run.sh spike      400 usuários em 10 s
#   ./run.sh soak       50 usuários por 1 h
#   ./run.sh read       só leitura (tag read)
#   ./run.sh api        só a API do monolito
#   ./run.sh journey    ciclo completo de carona (sem chat)
#   ./run.sh auth       só Keycloak (tag auth): logins + refresh
#   ./run.sh web        interface web em http://localhost:8089
#
# Variáveis: LOAD_USERS (default 1200), LOAD_SPAWN (120), JOURNEY_USERS (20),
#             LOAD_HOLD, SMOKE_HOLD, DURATION (s), PROCS, PROCS,
#             RAIJI_GATEWAY, RAIJI_ENABLE_WRITES...
# =============================================================================
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PY=.venv/bin/python
LOCUST=.venv/bin/locust
STAMP=$(date +%Y%m%d-%H%M%S)
REPORTS=reports

# Padrões: 1200 usuários virtuais. Para outro valor, LOAD_USERS=...
USERS="${LOAD_USERS:-1200}"
SPAWN="${LOAD_SPAWN:-120}"
# Jornada de escrita: começa menor por segurança (cria caronas/reservas/mensagens)
JOURNEY_USERS="${JOURNEY_USERS:-20}"

[ -f .env ] || { cp .env.example .env; echo "[i] .env criado a partir de .env.example"; }

# Carrega .env SEM sobrescrever o que veio da linha de comando.
load_env() {
  local line key value
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    key=${line%%=*}
    value=${line#*=}
    case "$key" in *[!A-Za-z0-9_]*|'') continue ;; esac
    [ -n "${!key+x}" ] || export "$key=$value"
  done < .env
}
load_env

deps() {
  [ -d .venv ] || python3 -m venv .venv
  .venv/bin/pip -q install --upgrade pip
  .venv/bin/pip -q install -r requirements.txt
  echo "[ok] $(.venv/bin/locust --version)"
}

seed() { "$PY" scripts/seed_test_users.py; }
check() { "$PY" scripts/preflight.py; }

locust_run() {
  mkdir -p "$REPORTS"
  local -a procs=()
  # Acima de ~300 VUs, distribua em processos: PROCS=2..4 ./run.sh load
  if [ "${PROCS:-1}" -gt 1 ] 2>/dev/null; then
    # Cada suíte precisa de portas próprias: o master do Locust usa 5557 por
    # padrão e, rodando várias suítes em paralelo, os workers se cruzariam.
    procs=(--processes "$PROCS"
           --master-bind-port "${MASTER_PORT:-5557}"
           --master-port "${MASTER_PORT:-5557}")
  fi
  "$LOCUST" -f locustfile.py --headless --only-summary \
    --host "${RAIJI_GATEWAY:-http://localhost:8000}" \
    "${procs[@]}" \
    --html "$REPORTS/$1-$STAMP.html" \
    --csv "$REPORTS/$1-$STAMP" \
    "${@:2}"
}

slice_run() { # nome tag usuários spawn segundos
  local secs="${5%s}"
  LOAD_PROFILE=smoke LOAD_USERS="$3" LOAD_SPAWN="$4" LOAD_HOLD="$secs" \
    locust_run "$1" -u "$3" -r "$4" -t "${secs}s" --tags "$2" "${@:6}"
}

cmd="${1:-help}"; shift || true
case "$cmd" in
  deps)      deps ;;
  check)     check ;;
  seed)      seed ;;
  smoke)     LOAD_PROFILE=smoke  LOAD_USERS="$USERS" LOAD_SPAWN="$SPAWN" \
               locust_run smoke  -u "$USERS" -r "$SPAWN" -t "${SMOKE_HOLD:-60}s" "$@" ;;
  load)      LOAD_PROFILE=load   LOAD_USERS="$USERS" LOAD_SPAWN="$SPAWN" \
               locust_run load   -u "$USERS" -r "$SPAWN" \
               -t "$(( ${LOAD_RAMP:-60} + ${LOAD_HOLD:-360} ))s" "$@" ;;
  stress)    LOAD_PROFILE=stress LOAD_USERS="$USERS" LOAD_SPAWN="$SPAWN" \
               locust_run stress -u "$USERS" -r "$SPAWN" "$@" ;;
  spike)     LOAD_PROFILE=spike  LOAD_USERS="$USERS" LOAD_SPAWN="$SPAWN" \
               locust_run spike  -u "$USERS" -r "$SPAWN" -t "${SPIKE_HOLD:-120}s" "$@" ;;
  soak)      LOAD_PROFILE=soak   LOAD_USERS="$USERS" LOAD_SPAWN="$SPAWN" \
               locust_run soak   -u "$USERS" -r "$SPAWN" -t "${SOAK_HOLD:-3600}s" "$@" ;;
  read)      slice_run read    read    "$USERS" "$SPAWN" "${DURATION:-300}" "$@" ;;
  auth)      slice_run auth    auth    "$USERS" "$SPAWN" "${DURATION:-120}" "$@" ;;
  api)    slice_run api     api     "$USERS" "$SPAWN" "${DURATION:-180}" "$@" ;;
  journey)    slice_run journey journey "$JOURNEY_USERS" "$SPAWN" "${DURATION:-300}" "$@" ;;
  web)       mkdir -p "$REPORTS"; exec "$LOCUST" -f locustfile.py --class-picker \
               --host "${RAIJI_GATEWAY:-http://localhost:8000}" "$@" ;;
  *) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
