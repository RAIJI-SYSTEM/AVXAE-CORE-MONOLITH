#!/usr/bin/env bash
# =============================================================================
# Testes de carga de MENSAGERIA (AMQP/RabbitMQ) — Locust
#
#   ./run-messaging.sh deps     instala Locust + pika no .venv local
#   ./run-messaging.sh smoke    5 usuários, 20 s (push + probe)
#   ./run-messaging.sh load     30 usuários, rampa 3/s, 120 s
#   ./run-messaging.sh ride     só eventos de carona (tag ride)
#   ./run-messaging.sh user     só eventos de usuário (tag user)
#   ./run-messaging.sh egress   só message.created (tag egress)
#   ./run-messaging.sh probe    só ida e volta no raiji.chat (tag probe)
#   ./run-messaging.sh web      interface web em http://localhost:8089
#
# Variáveis principais (ver .env.example):
#   RAIJI_AMQP_URL          amqp://raiji:raiji123@localhost:5672/%2f
#   RAIJI_MESSAGING_TAGS    tags padrão, separadas por espaço (aqui: egress probe)
#   AMQP_USERS / AMQP_SPAWN / AMQP_HOLD
# =============================================================================
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PY=.venv/bin/python
LOCUST=.venv/bin/locust
STAMP=$(date +%Y%m%d-%H%M%S)
REPORTS=reports

AMQP_USERS="${AMQP_USERS:-5}"
AMQP_SPAWN="${AMQP_SPAWN:-1}"
AMQP_HOLD="${AMQP_HOLD:-20}"
TAGS="${RAIJI_MESSAGING_TAGS:-egress probe}"

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
  # Reaplica os defaults de tags se o .env não definir.
  TAGS="${RAIJI_MESSAGING_TAGS:-$TAGS}"
}
load_env

deps() {
  [ -d .venv ] || python3 -m venv .venv
  .venv/bin/pip -q install --upgrade pip
  .venv/bin/pip -q install -r requirements.txt
  echo "[ok] $(.venv/bin/locust --version) / pika $(.venv/bin/python -c 'import pika; print(pika.__version__)')"
}

locust_run() { # nome [args...]
  mkdir -p "$REPORTS"
  "$LOCUST" -f messaging_locustfile.py --headless --only-summary \
    --html "$REPORTS/$1-$STAMP.html" \
    --csv "$REPORTS/$1-$STAMP" \
    "${@:2}"
}

cmd="${1:-help}"; shift || true

case "$cmd" in
  deps)   deps ;;
  smoke)  locust_run "amqp-smoke" -u "$AMQP_USERS" -r "$AMQP_SPAWN" -t "${AMQP_HOLD}s" --tags $TAGS "$@" ;;
  load)   locust_run "amqp-load"  -u "${AMQP_LOAD_USERS:-30}" -r "${AMQP_LOAD_SPAWN:-3}" \
            -t "${AMQP_LOAD_HOLD:-120}s" --tags $TAGS "$@" ;;
  ride)   locust_run "amqp-ride"  -u "$AMQP_USERS" -r "$AMQP_SPAWN" -t "${AMQP_HOLD}s" --tags ride "$@" ;;
  user)   locust_run "amqp-user"  -u "$AMQP_USERS" -r "$AMQP_SPAWN" -t "${AMQP_HOLD}s" --tags user "$@" ;;
  egress) locust_run "amqp-chat"  -u "$AMQP_USERS" -r "$AMQP_SPAWN" -t "${AMQP_HOLD}s" --tags egress "$@" ;;
  probe)  locust_run "amqp-probe" -u "$AMQP_USERS" -r "$AMQP_SPAWN" -t "${AMQP_HOLD}s" --tags probe "$@" ;;
  web)    mkdir -p "$REPORTS"; exec "$LOCUST" -f messaging_locustfile.py --class-picker "$@" ;;
  *)      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
