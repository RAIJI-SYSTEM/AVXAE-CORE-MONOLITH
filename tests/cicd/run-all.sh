#!/usr/bin/env bash
# =============================================================================
# run-all.sh — suíte de verificação da S1-03.
#   1. bash -n (sintaxe) em todos os scripts
#   2. actionlint nos workflows (se disponível; senão, tenta baixar; senão, avisa)
#   3. testes estruturais (python3 + unittest) — YAML, gatilhos, etapas, docs
#   4. teste funcional do smoke-test.sh contra um servidor mock (passa E falha)
#   5. idempotência do setup-cicd.sh (rodar 2x não muda nada)
# Uso: tests/cicd/run-all.sh [--quick]
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
QUICK="${1:-}"
FAILURES=0

GRN=$'\033[0;32m'; RED=$'\033[0;31m'; YLW=$'\033[1;33m'; NC=$'\033[0m'
step() { printf '\n%s==>%s %s\n' "$YLW" "$NC" "$*"; }
ok()   { printf '%s OK %s %s\n' "$GRN" "$NC" "$*"; }
ko()   { printf '%sFAIL%s %s\n' "$RED" "$NC" "$*"; FAILURES=$((FAILURES+1)); }

# --- PYTHONPATH p/ PyYAML (usa .tools se o sistema não tiver) --------------------
export PYTHONPATH="${PYTHONPATH:-}"
if ! python3 -c 'import yaml' 2>/dev/null; then
  for cand in "$ROOT/.tools/pylibs" "$ROOT/../.tools/pylibs"; do
    if PYTHONPATH="$cand" python3 -c 'import yaml' 2>/dev/null; then
      export PYTHONPATH="$cand${PYTHONPATH:+:$PYTHONPATH}"
      break
    fi
  done
fi

# --- 1) sintaxe dos scripts ------------------------------------------------------
step "1/5 · Sintaxe bash (bash -n)"
while IFS= read -r f; do
  if bash -n "$f" 2>/dev/null; then ok "$f"; else ko "$f (sintaxe inválida)"; bash -n "$f"; fi
done < <(find scripts tests -name '*.sh' -type f | sort)

# --- 2) actionlint ----------------------------------------------------------------
step "2/5 · actionlint (workflows)"
ACTIONLINT=""
for cand in "$(command -v actionlint || true)" "$ROOT/.tools/actionlint" "$ROOT/../.tools/actionlint"; do
  [[ -n "$cand" && -x "$cand" ]] && { ACTIONLINT="$cand"; break; }
done
if [[ -z "$ACTIONLINT" ]]; then
  mkdir -p "$ROOT/.tools"
  if curl -fsSL -o "$ROOT/.tools/actionlint.tgz" \
      https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz 2>/dev/null; then
    tar xzf "$ROOT/.tools/actionlint.tgz" -C "$ROOT/.tools" actionlint && chmod +x "$ROOT/.tools/actionlint"
    ACTIONLINT="$ROOT/.tools/actionlint"
  fi
fi
if [[ -n "$ACTIONLINT" ]]; then
  if "$ACTIONLINT" -no-color .github/workflows/*.yml; then
    ok "actionlint: workflows válidos"
  else
    ko "actionlint apontou problemas nos workflows"
  fi
else
  printf '%sWARN%s actionlint indisponível (sem rede?) — etapa pulada\n' "$YLW" "$NC"
fi

# --- 3) testes estruturais ----------------------------------------------------------
step "3/5 · Testes estruturais (python unittest)"
if python3 -m unittest discover -s tests/cicd -p 'test_*.py' -v 2>&1 | tail -n 25; then
  if python3 -m unittest discover -s tests/cicd -p 'test_*.py' >/dev/null 2>&1; then
    ok "testes estruturais passaram"
  else
    ko "testes estruturais falharam"
  fi
else
  ko "testes estruturais falharam (execução)"
fi

# --- 4) teste funcional do smoke-test ------------------------------------------------
step "4/5 · Teste funcional do smoke-test.sh (mock server)"
if [[ "$QUICK" == "--quick" ]]; then
  printf '%sWARN%s --quick: pulando teste funcional\n' "$YLW" "$NC"
elif bash tests/cicd/test_smoke_functional.sh; then
  ok "smoke-test.sh se comporta corretamente (cenários positivos e negativos)"
else
  ko "teste funcional do smoke-test.sh falhou"
fi

# --- 5) idempotência do setup ----------------------------------------------------------
step "5/5 · Idempotência do setup-cicd.sh"
SNAP_BEFORE="$(mktemp)"; SNAP_AFTER="$(mktemp)"
find .github scripts tests docs infra docker-compose.aws.yml -type f \( -name '*.yml' -o -name '*.sh' -o -name '*.py' -o -name '*.md' -o -name '*.env' \) \
  -exec sha256sum {} + | sort > "$SNAP_BEFORE"
bash scripts/setup-cicd.sh >/dev/null 2>&1
find .github scripts tests docs infra docker-compose.aws.yml -type f \( -name '*.yml' -o -name '*.sh' -o -name '*.py' -o -name '*.md' -o -name '*.env' \) \
  -exec sha256sum {} + | sort > "$SNAP_AFTER"
if diff -q "$SNAP_BEFORE" "$SNAP_AFTER" >/dev/null; then
  ok "rodar setup-cicd.sh duas vezes não altera nada (idempotente)"
else
  ko "setup-cicd.sh NÃO é idempotente"; diff "$SNAP_BEFORE" "$SNAP_AFTER" | head -20
fi
rm -f "$SNAP_BEFORE" "$SNAP_AFTER"

# --- resumo ------------------------------------------------------------------------
echo
if [[ "$FAILURES" -eq 0 ]]; then
  printf '%s============================================%s\n' "$GRN" "$NC"
  printf '%s TODOS OS TESTES DA S1-03 PASSARAM ✅%s\n' "$GRN" "$NC"
  printf '%s============================================%s\n' "$GRN" "$NC"
  exit 0
fi
printf '%s============================================%s\n' "$RED" "$NC"
printf '%s %d etapa(s) falharam ❌%s\n' "$RED" "$FAILURES" "$NC"
printf '%s============================================%s\n' "$RED" "$NC"
exit 1
