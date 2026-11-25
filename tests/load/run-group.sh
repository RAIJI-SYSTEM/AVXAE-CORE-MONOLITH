#!/usr/bin/env bash
# =============================================================================
# run-group.sh — roda as suítes dos TRÊS repositórios em paralelo.
#
# Fica aqui (tests/load do monolito) para que o teste "em grupo" continue
# reproduzível sem depender de pasta compartilhada: cada suíte roda o seu
# próprio run.sh, no seu diretório, com o seu próprio .venv.
#
#   ./run-group.sh smoke     # 600 + 400 + 200 = 1200 VUs, 90 s
#   ./run-group.sh load      # 600 + 400 + 200 = 1200 VUs, 6 min
#   ./run-group.sh read      # 600 + 400 + 200 = 1200 VUs, somente leitura, 60 s
#
# Escalar para 1200 usuários simultâneos (leitura, sem criar dados):
#   PROCS=2 LOAD_SPAWN=60 HOLD=90 ./run-group.sh read
#
# Variáveis: HOLD (s), USERS_MONO, USERS_CHAT, USERS_FRONT, PROCS, LOAD_SPAWN.
# Relatórios: <repo>/reports (HTML+CSV) e resumo em
#             tests/load/reports/group-<timestamp>/.
# =============================================================================
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)          # ~/Documentos/integrador-raiji
MONO="$HERE"
CHAT="$ROOT/MESSAGE-SERVICE/load-tests"
FRONT="$ROOT/FRONT-PWA/load-tests"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="$HERE/reports/group-$STAMP"
mkdir -p "$OUT"

for dir in "$MONO" "$CHAT" "$FRONT"; do
  [ -x "$dir/.venv/bin/locust" ] || { echo "[erro] sem .venv em $dir — rode ./run.sh deps lá"; exit 1; }
done

run_suite() { # nome dir comando usuários porta-do-master
  local name=$1 dir=$2 cmd=$3 users=$4 port=${5:-5557}
  (
    cd "$dir" || exit 1
    MASTER_PORT="$port" LOAD_USERS="$users" LOAD_HOLD="${HOLD:-90}" DURATION="${HOLD:-90}" \
      ./run.sh "$cmd"
  ) > "$OUT/$name.log" 2>&1
  echo "  [$name] exit=$? -> $OUT/$name.log"
}

profile="${1:-smoke}"
case "$profile" in
  smoke)
    HOLD="${HOLD:-90}"
    u_mono="${USERS_MONO:-600}"; u_chat="${USERS_CHAT:-400}"; u_front="${USERS_FRONT:-200}"
    ;;
  load)
    HOLD="${HOLD:-360}"
    u_mono="${USERS_MONO:-600}"; u_chat="${USERS_CHAT:-400}"; u_front="${USERS_FRONT:-200}"
    ;;
  read)
    # somente leitura: não cria caronas, reservas nem mensagens
    HOLD="${HOLD:-60}"
    u_mono="${USERS_MONO:-600}"; u_chat="${USERS_CHAT:-400}"; u_front="${USERS_FRONT:-200}"
    ;;
  *)
    sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac

total=$((u_mono + u_chat + u_front))
echo "== grupo ($profile): monolito=$u_mono chat=$u_chat front=$u_front = $total VUs, ${HOLD}s, PROCS=${PROCS:-1}"
run_suite monolito "$MONO" "$profile" "$u_mono" 5557 & p1=$!
run_suite chat     "$CHAT" "$profile" "$u_chat" 5587 & p2=$!
run_suite front    "$FRONT" "$profile" "$u_front" 5617 & p3=$!
wait $p1; r1=$?
wait $p2; r2=$?
wait $p3; r3=$?

echo
printf '%-10s %8s %8s %8s %8s\n' "suíte" "reqs" "falhas" "mediana" "p95"
for name in monolito chat front; do
  case "$name" in
    monolito) dir="$MONO" ;;
    chat)     dir="$CHAT" ;;
    front)    dir="$FRONT" ;;
  esac
  csv=$(ls -t "$dir"/reports/*_stats.csv 2>/dev/null | head -1)
  [ -n "$csv" ] || continue
  awk -F, -v n="$name" '$2=="Aggregated"{printf "%-10s %8s %8s %8s %8s\n", n, $3, $4, $5, $17}' "$csv"
done
echo
echo "== exit por suíte: monolito=$r1 chat=$r2 front=$r3"
[ "$r1" -eq 0 ] && [ "$r2" -eq 0 ] && [ "$r3" -eq 0 ] || exit 1
