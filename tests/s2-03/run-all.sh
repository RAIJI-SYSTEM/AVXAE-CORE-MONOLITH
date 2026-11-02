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
