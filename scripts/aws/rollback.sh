#!/usr/bin/env bash
# =============================================================================
# rollback.sh — procedimento de rollback da issue S1-03:
#   0) Verifica se EXISTE versão anterior (<env>-previous no ECR).
#      Sem anterior -> para o app na EC2 (não serve versão quebrada) e falha:
#      o ambiente fica offline até uma versão correta ser publicada.
#   1) RETAG no ECR: <env>-previous -> <env>-current (sem re-upload)
#   2) REDEPLOY na EC2 (compose pull + up -d + health-gate)
#   3) SMOKE TEST completo (paralelo) para confirmar a recuperação
#
# Uso (runner ou máquina com aws cli + acesso SSH):
#   ./scripts/aws/rollback.sh [--auto]
# Requer (env): ENVIRONMENT, EC2_HOST, EC2_USER, SSH_PRIVATE_KEY,
#               AWS_REGION_NAME, BASE_PORT, BASE_URL, SMOKE_USERNAME/SMOKE_PASSWORD
# =============================================================================
set -euo pipefail

: "${ENVIRONMENT:?ENVIRONMENT obrigatório (dev|homolog)}"
: "${EC2_HOST:?EC2_HOST obrigatório}"
: "${EC2_USER:=ubuntu}"
: "${SSH_PRIVATE_KEY:?SSH_PRIVATE_KEY obrigatório}"
: "${AWS_REGION_NAME:=us-east-1}"
: "${BASE_PORT:?BASE_PORT obrigatório}"
BASE_URL="${BASE_URL:-http://$EC2_HOST:$BASE_PORT}"
REPO="${ECR_REPOSITORY:-raiji-app}"

AUTO="${1:-}"
log() { echo "[rollback:$ENVIRONMENT] $*"; }
die() { echo "[rollback:$ENVIRONMENT] ERRO: $*" >&2; exit 1; }

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)" || die "aws sts falhou"
ECR_REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION_NAME}.amazonaws.com"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- SSH (chave temporária) ------------------------------------------------------
KEY_FILE="$(mktemp)"; umask 077; printf '%s\n' "$SSH_PRIVATE_KEY" > "$KEY_FILE"
cleanup() { rm -f "$KEY_FILE"; }
trap cleanup EXIT
SSH_OPTS=(-i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=15)

# --- 0) Existe versão anterior? ----------------------------------------------------
log "verificando imagem anterior (${ENVIRONMENT}-previous) no ECR ..."
PREV="$(aws ecr describe-images --region "$AWS_REGION_NAME" --repository-name "$REPO" \
  --image-ids imageTag="${ENVIRONMENT}-previous" \
  --query 'imageDetails[0].imageTags[0]' --output text 2>/dev/null || true)"

if [[ -z "$PREV" || "$PREV" == "None" ]]; then
  log "NÃO existe versão anterior para reverter."
  log "Parando o app na EC2 para não servir versão quebrada ..."
  ssh "${SSH_OPTS[@]}" "$EC2_USER@$EC2_HOST" "ENVIRONMENT='$ENVIRONMENT' bash -s" <<'STOP_EOF' || true
set -u
cd "/opt/raiji/$ENVIRONMENT" 2>/dev/null || exit 0
set -a; . ./.env 2>/dev/null; set +a
docker compose -p "${COMPOSE_PROJECT_NAME:-raiji-$ENVIRONMENT}" \
  -f docker-compose.yml -f docker-compose.aws.yml stop app 2>/dev/null || true
STOP_EOF
  die "ambiente SEM histórico de rollback — mantido offline até que uma versão correta suba (release bloqueado)."
fi

# --- 1) RETAG da imagem anterior ---------------------------------------------------
log "retag ${ENVIRONMENT}-previous -> ${ENVIRONMENT}-current no ECR ..."
"$SCRIPT_DIR/ecr-retag.sh" "$ECR_REGISTRY/$REPO" \
  "${ENVIRONMENT}-previous" "${ENVIRONMENT}-current" --region "$AWS_REGION_NAME" \
  || die "retag falhou"

# --- 2) REDEPLOY na EC2 -------------------------------------------------------------
log "redeploy na EC2 ($EC2_USER@$EC2_HOST) ..."
ssh "${SSH_OPTS[@]}" "$EC2_USER@$EC2_HOST" "ENVIRONMENT='$ENVIRONMENT' bash -s" <<'REMOTE_EOF' || die "redeploy remoto falhou"
set -euo pipefail
cd "/opt/raiji/$ENVIRONMENT"
set -a; . ./.env; set +a
COMPOSE=(docker compose -p "$COMPOSE_PROJECT_NAME" -f docker-compose.yml -f docker-compose.aws.yml)
aws ecr get-login-password --region "${AWS_REGION:-us-east-1}" | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
"${COMPOSE[@]}" pull --quiet app
IMAGE_TAG="${ENVIRONMENT}-current" "${COMPOSE[@]}" up -d --no-deps app
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=${ENVIRONMENT}-current/" .deploy-state 2>/dev/null || true
BASE_URL="http://localhost:$BASE_PORT" ./scripts/smoke-test.sh --check health --retries 36 --retry-delay 10
REMOTE_EOF

# --- 3) SMOKE completo (paralelo) ----------------------------------------------------
log "smoke test completo em $BASE_URL ..."
SMOKE_USERNAME="${SMOKE_USERNAME:-teste}" SMOKE_PASSWORD="${SMOKE_PASSWORD:-teste123}" \
BASE_URL="$BASE_URL" "$SCRIPT_DIR/../smoke-test.sh" --parallel --retries 6 --retry-delay 10 \
  || die "smoke pós-rollback falhou — intervenção manual necessária (veja docs/DEPLOY.md)"

log "rollback concluído com sucesso. Ambiente restaurado: $BASE_URL"
[[ "$AUTO" == "--auto" ]] && log "(executado automaticamente)"
exit 0
