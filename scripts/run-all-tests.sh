#!/usr/bin/env bash
# Executa todos os testes do monólito e centraliza os relatórios HTML em
# reports/testes/. Requer Java 21, Maven e Docker para os ITs.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/reports/testes"
LOGS="$OUT/logs"
mkdir -p "$OUT" "$LOGS"
rm -rf "$OUT"/testes.html "$OUT"/summary.json "$OUT"/cobertura "$OUT"/mutacao "$OUT"/e2e
status=0

run_log() {
  local name="$1"; shift
  echo "→ $name"
  "$@" > "$LOGS/$name.log" 2>&1
  local rc=$?
  echo "  $name: exit $rc"
  [ "$rc" -ne 0 ] && status=1
  return 0
}

run_log "01-maven-unitarios-integracao" mvn -B -ntp clean verify

python3 "$ROOT/scripts/reporting/generate_maven_report.py" "$ROOT" "$OUT"

if [ -d "$ROOT/raiji-app/target/site/jacoco-aggregate" ]; then
  cp -r "$ROOT/raiji-app/target/site/jacoco-aggregate" "$OUT/cobertura"
elif [ -d "$ROOT/raiji-app/target/site/jacoco" ]; then
  cp -r "$ROOT/raiji-app/target/site/jacoco" "$OUT/cobertura"
fi

run_log "02-pit-mutacao" \
  mvn -B -ntp -pl raiji-app,raiji-rides,raiji-users -am test-compile \
  org.pitest:pitest-maven:mutationCoverage

mkdir -p "$OUT/mutacao"
for module in raiji-app raiji-rides raiji-users; do
  if [ -d "$ROOT/$module/target/pit-reports" ]; then
    cp -r "$ROOT/$module/target/pit-reports" "$OUT/mutacao/$module"
  fi
done

if curl -fsS --max-time 3 http://localhost:8000/actuator/health >/dev/null 2>&1; then
  if [ ! -d "$ROOT/e2e/node_modules" ]; then
    (cd "$ROOT/e2e" && npm install --no-audit --no-fund) > "$LOGS/e2e-install.log" 2>&1
  fi
  (cd "$ROOT/e2e" && npx playwright test --reporter=html) > "$LOGS/03-e2e-playwright.log" 2>&1
  e2e_rc=$?
  echo "  03-e2e-playwright: exit $e2e_rc"
  [ "$e2e_rc" -ne 0 ] && status=1
  [ -d "$ROOT/e2e/playwright-report" ] && cp -r "$ROOT/e2e/playwright-report" "$OUT/e2e"
else
  echo "  03-e2e-playwright: ignorado (stack não está em http://localhost:8000)" > "$LOGS/03-e2e-playwright.log"
  echo "E2E não executado: suba a stack do docker-compose primeiro." >> "$LOGS/03-e2e-playwright.log"
fi

python3 "$ROOT/scripts/reporting/build_index.py" "$OUT" maven

echo
echo "Relatórios em: $OUT/index.html"
exit "$status"
