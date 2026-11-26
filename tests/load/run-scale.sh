#!/usr/bin/env bash
# =============================================================================
# Comparativo 1×N do AVXAE-CORE-MONOLITH (API) com Locust
#
# Diferente do chat/front, o monólito não é iniciado por este script (precisa de
# Postgres/Keycloak). Informe as instâncias já em execução:
#
#   RAIJI_SCALE_URLS="http://host:8000,http://host:8001" \
#   RAIJI_SCALE_RESULTS=../observability/exporter/results/scale.json \
#   N_LIST="1 2" USERS=100 HOLD=60 ./run-scale.sh
#
# Para remar o CV: o valor de N_LIST deve corresponder ao nº de URLs.
# =============================================================================
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
HERE=$(pwd)

if [ -z "${RAIJI_SCALE_URLS:-}" ]; then
  echo "[!] Defina RAIJI_SCALE_URLS com as instâncias já em execução."
  echo "    Ex.: RAIJI_SCALE_URLS=\"http://127.0.0.1:8000,http://127.0.0.1:8001\""
  exit 2
fi

N_LIST="${N_LIST:-}"
USERS="${USERS:-40}"
USERS_PER_INSTANCE="${USERS_PER_INSTANCE:-}"
SPAWN="${SPAWN:-5}"
HOLD="${HOLD:-20}"
PROCS="${PROCS:-1}"
RESULTS_FILE="${RAIJI_SCALE_RESULTS:-$HERE/scale-results.json}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3300}"
GRAFANA_AUTH="${GRAFANA_AUTH:-admin:admin}"
ANNOTATE="${ANNOTATE:-1}"
STAMP=$(date +%Y%m%d-%H%M%S)
REPORTS=reports
mkdir -p "$REPORTS"

PY=python3
[ -x .venv/bin/python ] && PY=.venv/bin/python
LOCUST=.venv/bin/locust
[ -x "$LOCUST" ] || LOCUST="locust"
command -v "$LOCUST" >/dev/null 2>&1 || { echo "[!] Locust não instalado (run.sh deps)"; exit 1; }

urls_list=()
IFS=',' read -r -a urls_list <<< "$RAIJI_SCALE_URLS"
if [ -z "$N_LIST" ]; then
  N_LIST="${#urls_list[@]}"
fi

annotate() {
  [ "$ANNOTATE" = 1 ] || return 0
  curl -sf -u "$GRAFANA_AUTH" -H 'Content-Type: application/json' \
    -X POST "$GRAFANA_URL/api/annotations" \
    -d "{\"text\":\"$1\",\"tags\":[\"raiji-scale\",\"api\"]}" >/dev/null 2>&1 || true
}

for n in $N_LIST; do
  if [ "$n" -gt "${#urls_list[@]}" ]; then
    echo "[!] N=$n maior que o número de URLs (${#urls_list[@]})"; continue
  fi
  urls=$(printf '%s,' "${urls_list[@]:0:$n}"); urls=${urls%,}
  echo
  echo "[i] ===== API N=$n instância(s) · $USERS usuários · ${HOLD}s ====="
  if [ -n "$USERS_PER_INSTANCE" ]; then
    run_users=$((n * USERS_PER_INSTANCE)); mode="por instância ($USERS_PER_INSTANCE cada)"
  else
    run_users=$USERS; mode="carga fixa"
  fi
  echo "[i] carga total: $run_users usuários ($mode)"
  annotate "Início escala API N=$n (users=$run_users)"
  procs_args=()
  [ "$PROCS" -gt 1 ] && procs_args=(--processes "$PROCS" --master-bind-port "${MASTER_PORT:-5557}")
  RAIJI_SCALE_URLS="$urls" \
  RAIJI_SCALE_TOKEN="${RAIJI_SCALE_TOKEN:-}" \
  RAIJI_SCALE_PATH="${RAIJI_SCALE_PATH:-}" \
    "$LOCUST" -f scale_locustfile.py --headless --only-summary \
      --html "$REPORTS/api-scale-n$n-$STAMP.html" \
      --csv "$REPORTS/api-scale-n$n-$STAMP" \
      "${procs_args[@]}" -u "$run_users" -r "$SPAWN" -t "${HOLD}s"
  rc=$?
  annotate "Fim escala API N=$n"
  csv="$REPORTS/api-scale-n$n-${STAMP}_stats.csv"
  [ -f "$csv" ] && "$PY" scale_report.py "$csv" "$n" "$RESULTS_FILE"
  [ "$rc" -eq 0 ] || echo "[i] Locust terminou com código $rc"
done

echo "[ok] API: resultados em $RESULTS_FILE"
