#!/usr/bin/env bash
# =============================================================================
# setup-cicd.sh — NÃO gera mais arquivos (modo somente verificação).
#
# Histórico: este script era um gerador "tudo-em-um" que sobrescrevia os
# workflows e os scripts de deploy. Ele ficou desatualizado e, ao rodar,
# APAGAVA correções mais novas (ex.: `build: !reset`, `PUBLIC_BASE_URL`,
# sincronização do RabbitMQ). Para evitar regressão, ele agora apenas:
#
#   1. verifica se todos os arquivos de CI/CD existem;
#   2. imprime os próximos passos.
#
# É idempotente por construção: nunca escreve em arquivos versionados.
# Uso: ./scripts/setup-cicd.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

REQUIRED=(
  .github/workflows/ci.yml
  .github/workflows/deploy.yml
  .github/workflows/deploy-dev.yml
  .github/workflows/deploy-homolog.yml
  .github/workflows/rollback.yml
  .github/workflows/guardian.yml
  scripts/smoke-test.sh
  scripts/aws/deploy.sh
  scripts/aws/deploy-remote.sh
  scripts/aws/rollback.sh
  scripts/aws/ecr-retag.sh
  scripts/aws/bootstrap-aws.sh
  scripts/aws/guardian.sh
  docker-compose.aws.yml
  docs/DEPLOY.md
  docs/CD-CHECKLIST.md
  infra/aws/aws-config.env
)

missing=0
for f in "${REQUIRED[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "[setup-cicd] FALTANDO: $f" >&2
    missing=1
  fi
done

if [[ "$missing" -ne 0 ]]; then
  echo "[setup-cicd] ERRO: arquivos de CI/CD ausentes (veja acima)." >&2
  exit 1
fi

cat <<'FIM'

[setup-cicd] OK — todos os arquivos de CI/CD estão presentes.
[setup-cicd] Este script não gera nem sobrescreve nada (somente verificação).

Próximos passos para o CD funcionar:
  1) ./scripts/aws/bootstrap-aws.sh --env dev
     ./scripts/aws/bootstrap-aws.sh --env homolog
  2) Configure os secrets/variables no GitHub (veja docs/CD-CHECKLIST.md)
  3) ./scripts/aws/check-github-config.sh   (requer gh autenticado)
  4) git push origin develop                (dispara o Deploy DEV)
  5) git push origin develop:main           (sincroniza main -> HOMOLOG/Guardian)

FIM
