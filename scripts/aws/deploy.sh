#!/usr/bin/env bash
# =============================================================================
# deploy.sh — publica um ambiente na EC2 (roda no GitHub Actions runner).
#
# Requer (env):
#   ENVIRONMENT (dev|homolog), EC2_HOST, EC2_USER (ubuntu), SSH_PRIVATE_KEY,
#   AWS_REGION_NAME, BASE_PORT; segredos: DB_PASSWORD, KEYCLOAK_ADMIN_CLIENT_SECRET
# Requer (ferramentas): aws cli (credenciais já configuradas), ssh, tar.
#
# Passos:
#   1. descobre o registry ECR da conta
#   2. sincroniza os arquivos de infra para /opt/raiji/<env> na EC2
#   3. grava o .env remoto (variáveis do provedor — nunca commitado)
#   4. executa deploy-remote.sh (pull da imagem, up -d, health-gate, self-rollback)
# =============================================================================
set -euo pipefail

: "${ENVIRONMENT:?ENVIRONMENT obrigatório (dev|homolog)}"
: "${EC2_HOST:?EC2_HOST obrigatório (defina a variável AWS_EC2_HOST_${ENVIRONMENT^^} no GitHub)}"
: "${EC2_USER:=ubuntu}"
: "${SSH_PRIVATE_KEY:?SSH_PRIVATE_KEY obrigatório}"
: "${AWS_REGION_NAME:=us-east-1}"
: "${BASE_PORT:?BASE_PORT obrigatório (dev=8000, homolog=9000)}"
: "${DB_PASSWORD:?DB_PASSWORD obrigatório}"
: "${KEYCLOAK_ADMIN_CLIENT_SECRET:?KEYCLOAK_ADMIN_CLIENT_SECRET obrigatório}"

REMOTE_DIR="/opt/raiji/$ENVIRONMENT"
KC_PORT="${KC_PORT:-$((BASE_PORT + 180))}"

log() { echo "[deploy:$ENVIRONMENT] $*"; }
die() { echo "[deploy:$ENVIRONMENT] ERRO: $*" >&2; exit 1; }

# --- 1) SSH key temporária ---------------------------------------------------
KEY_FILE="$(mktemp)"
umask 077; printf '%s\n' "$SSH_PRIVATE_KEY" > "$KEY_FILE"
cleanup() { rm -f "$KEY_FILE"; }
trap cleanup EXIT

SSH_OPTS=(-i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=15)
SSH_TARGET="$EC2_USER@$EC2_HOST"

# --- 2) Registry ECR ----------------------------------------------------------
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)" \
  || die "aws sts falhou — confira AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY"
ECR_REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION_NAME}.amazonaws.com"
IMAGE_TAG="${IMAGE_TAG:-${ENVIRONMENT}-current}"
log "ECR registry: $ECR_REGISTRY | tag: $IMAGE_TAG"

# --- 3) Sincronizar arquivos de infra -----------------------------------------
log "sincronizando arquivos para $SSH_TARGET:$REMOTE_DIR ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "mkdir -p '$REMOTE_DIR/scripts/aws'"
tar czf - \
  docker-compose.yml docker-compose.aws.yml \
  kong keycloak postgres \
  scripts/aws/deploy-remote.sh scripts/smoke-test.sh \
  | ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "tar xzf - -C '$REMOTE_DIR'"

# --- 4) Gravar .env remoto (stdin — segredos não aparecem em linha de comando)
log "gravando .env remoto (umask 077) ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "umask 077; cat > '$REMOTE_DIR/.env'" <<ENV_EOF
COMPOSE_PROJECT_NAME=raiji-$ENVIRONMENT
ENVIRONMENT=$ENVIRONMENT
ECR_REGISTRY=$ECR_REGISTRY
ECR_REPOSITORY=raiji-app
IMAGE_TAG=$IMAGE_TAG
BASE_PORT=$BASE_PORT
KC_PORT=$KC_PORT
SPRING_PROFILES_ACTIVE=docker
DB_URL=jdbc:postgresql://postgres:5432/raiji_app
DB_USER=raiji
DB_PASSWORD='$DB_PASSWORD'
POSTGRES_DB=raiji_keycloak
JWK_SET_URI=http://keycloak:8080/realms/raiji/protocol/openid-connect/certs
KEYCLOAK_ADMIN_CLIENT_SECRET='$KEYCLOAK_ADMIN_CLIENT_SECRET'
AWS_REGION=$AWS_REGION_NAME
ENV_EOF

# --- 5) Deploy remoto ----------------------------------------------------------
log "executando deploy-remote.sh na EC2 ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
  "cd '$REMOTE_DIR' && chmod +x scripts/aws/deploy-remote.sh scripts/smoke-test.sh && ./scripts/aws/deploy-remote.sh" \
  || die "deploy remoto falhou (health-gate). Se havia versão anterior, o self-rollback já foi tentado; veja logs acima."

log "deploy concluído com sucesso. URL: http://$EC2_HOST:$BASE_PORT"
