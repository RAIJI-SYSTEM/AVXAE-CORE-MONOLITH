#!/usr/bin/env bash
# =============================================================================
# deploy-remote.sh — roda NA instância EC2 (invocado via SSH pelo deploy.sh).
# Lê o .env do diretório, faz login no ECR, puxa a imagem nova, recria o stack,
# aguarda o health-gate e, se falhar, faz self-rollback para a imagem anterior.
# =============================================================================
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."   # /opt/raiji/<env>
[[ -f .env ]] || { echo "[remote] ERRO: .env não encontrado" >&2; exit 1; }
set -a; . ./.env; set +a

: "${COMPOSE_PROJECT_NAME:?}"
: "${IMAGE_TAG:?}"
: "${BASE_PORT:?}"

COMPOSE=(docker compose -p "$COMPOSE_PROJECT_NAME" -f docker-compose.yml -f docker-compose.aws.yml)
STATE_FILE=".deploy-state"
log() { echo "[remote:${ENVIRONMENT:-?}] $*"; }

# --- registra a versão atualmente em execução (para rollback) ------------------
PREVIOUS_TAG=""
if [[ -f "$STATE_FILE" ]]; then
  PREVIOUS_TAG="$(sed -n 's/^IMAGE_TAG=//p' "$STATE_FILE" | tail -n1)"
fi
if [[ -n "$PREVIOUS_TAG" && "$PREVIOUS_TAG" != "$IMAGE_TAG" ]]; then
  cp "$STATE_FILE" ".deploy-state.previous"
  log "versão anterior registrada: $PREVIOUS_TAG"
fi

# --- login no ECR (usa o instance profile da EC2) ------------------------------
if command -v aws >/dev/null 2>&1; then
  aws ecr get-login-password --region "${AWS_REGION:-us-east-1}" \
    | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null \
    && log "login ECR ok" || log "AVISO: login ECR falhou (seguindo assim mesmo)"
fi

# --- pull + up ------------------------------------------------------------------
log "pull das imagens (IMAGE_TAG=$IMAGE_TAG) ..."
"${COMPOSE[@]}" pull --quiet app || { echo "[remote] ERRO: pull da imagem falhou" >&2; exit 1; }

log "subindo o stack ..."
"${COMPOSE[@]}" up -d

# grava estado atual
grep -v '^IMAGE_TAG=' "$STATE_FILE" 2>/dev/null > "$STATE_FILE.tmp" || true
echo "IMAGE_TAG=$IMAGE_TAG" >> "$STATE_FILE.tmp"
echo "DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$STATE_FILE.tmp"
mv "$STATE_FILE.tmp" "$STATE_FILE"

# --- health-gate (reusa o smoke-test) --------------------------------------------
log "aguardando health-gate em http://localhost:$BASE_PORT ..."
if BASE_URL="http://localhost:$BASE_PORT" ./scripts/smoke-test.sh --check health --retries 36 --retry-delay 10; then
  log "health OK — deploy concluído."
  exit 0
fi

# --- self-rollback local ----------------------------------------------------------
echo "[remote] ERRO: health-gate falhou." >&2
if [[ -n "$PREVIOUS_TAG" ]]; then
  echo "[remote] self-rollback para $PREVIOUS_TAG ..." >&2
  IMAGE_TAG="$PREVIOUS_TAG" "${COMPOSE[@]}" up -d --no-deps app || true
  cp ".deploy-state.previous" "$STATE_FILE" 2>/dev/null || true
else
  # Sem versão anterior: NÃO servir versão quebrada — o app fica parado até
  # que um deploy correto seja publicado ("não deixe subir até subir certo").
  echo "[remote] SEM versão anterior — parando o app para não servir versão quebrada." >&2
  "${COMPOSE[@]}" stop app >/dev/null 2>&1 || true
fi
exit 1
