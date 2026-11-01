#!/usr/bin/env bash
# =============================================================================
# guardian.sh — vigia pós-deploy (proteção contra erros inesperados).
# Executado periodicamente pelo .github/workflows/guardian.yml:
#   1. Smoke completo (4 checagens em PARALELO)
#   2. Se falhar, aguarda GUARDIAN_RECHECK_DELAY e reconfirma (evita falso
#      positivo de rede/latência)
#   3. Falha confirmada + existe imagem <env>-previous -> ROLLBACK AUTOMÁTICO
#      (rollback.sh: retag + redeploy + smoke)
#   4. Sem histórico OU rollback falhou -> exit 1 (o workflow abre Issue)
#
# Requer (env): ENVIRONMENT, BASE_URL; para o rollback: EC2_HOST, EC2_USER,
#   SSH_PRIVATE_KEY, AWS_REGION_NAME, BASE_PORT, SMOKE_USERNAME/SMOKE_PASSWORD
# Saída: 0 = saudável (ou recuperado) | 1 = irrecuperável (gera alerta)
# =============================================================================
set -uo pipefail

: "${ENVIRONMENT:?ENVIRONMENT obrigatório}"
: "${BASE_URL:?BASE_URL obrigatório}"

GUARDIAN_RECHECK_DELAY="${GUARDIAN_RECHECK_DELAY:-60}"
GUARDIAN_RETRY="${GUARDIAN_RETRY:-3}"
GUARDIAN_DELAY="${GUARDIAN_DELAY:-5}"
GUARDIAN_RETRY_RECHECK="${GUARDIAN_RETRY_RECHECK:-6}"
GUARDIAN_DELAY_RECHECK="${GUARDIAN_DELAY_RECHECK:-10}"
REPO="${ECR_REPOSITORY:-raiji-app}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
log() { echo "[guardian:$ENVIRONMENT] $*"; }

smoke() { # <retries> <delay>
  BASE_URL="$BASE_URL" \
  SMOKE_USERNAME="${SMOKE_USERNAME:-teste}" \
  SMOKE_PASSWORD="${SMOKE_PASSWORD:-teste123}" \
    "$SCRIPT_DIR/../smoke-test.sh" --parallel --retries "$1" --retry-delay "$2"
}

log "smoke periódico em $BASE_URL"
if smoke "$GUARDIAN_RETRY" "$GUARDIAN_DELAY"; then
  log "ambiente saudável ✔"
  exit 0
fi

log "smoke FALHOU — reconfirmando em ${GUARDIAN_RECHECK_DELAY}s (evitar falso positivo) ..."
sleep "$GUARDIAN_RECHECK_DELAY"
if smoke "$GUARDIAN_RETRY_RECHECK" "$GUARDIAN_DELAY_RECHECK"; then
  log "falha transitória — ambiente saudável na reconfirmação ✔"
  exit 0
fi

log "falha CONFIRMADA. Verificando versão anterior no ECR ..."
PREV="$(aws ecr describe-images --region "${AWS_REGION_NAME:-us-east-1}" \
  --repository-name "$REPO" --image-ids imageTag="${ENVIRONMENT}-previous" \
  --query 'imageDetails[0].imageTags[0]' --output text 2>/dev/null || true)"

if [[ -n "$PREV" && "$PREV" != "None" ]]; then
  log "existe versão anterior (${ENVIRONMENT}-previous) — rollback automático ..."
  if "$SCRIPT_DIR/rollback.sh" --auto; then
    log "ambiente RECUPERADO via rollback ✔"
    exit 0
  fi
  log "rollback FALHOU — intervenção manual necessária."
  exit 1
fi

log "SEM versão anterior — impossível reverter automaticamente; gerando alerta."
exit 1
