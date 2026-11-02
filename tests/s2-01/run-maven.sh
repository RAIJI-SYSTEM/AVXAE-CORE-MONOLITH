#!/usr/bin/env bash
# =============================================================================
# run-maven.sh — validação REAL da S2-01 (compilação, testes unitários e IT)
# Requer: JDK 17+, Maven e Docker (Testcontainers/Jakarta para as ITs).
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

command -v mvn >/dev/null || { echo "[run-maven] mvn não encontrado." >&2; exit 1; }

echo "[run-maven] 1/2 — testes unitários do raiji-rides (+ deps)..."
mvn -q -pl raiji-rides -am test -Djacoco.skip=true

echo "[run-maven] 2/2 — integração BaggageApiIT no raiji-app..."
if docker info >/dev/null 2>&1; then
  mvn -q -pl raiji-app -am verify \
      -Dit.test=BaggageApiIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
      -Djacoco.skip=true
else
  echo "[run-maven] AVISO: docker indisponível — pulando ITs." >&2
  echo "[run-maven] Rode depois: mvn -pl raiji-app -am verify -Dit.test=BaggageApiIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false" >&2
fi

echo "[run-maven] cobertura agregada (gate 80% do raiji-app cobre os ≥70% do raiji-rides):"
echo "  mvn -q verify   # roda tudo com JaCoCo"
echo "[run-maven] OK"
