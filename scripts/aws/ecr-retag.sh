#!/usr/bin/env bash
# =============================================================================
# ecr-retag.sh — movc uma tag de imagem no ECR sem re-upload (retag).
# Uso: ecr-retag.sh <repo-completo> <tag-origem> <tag-destino> [--region r] [--if-missing=skip|fail]
# Exemplo: ecr-retag.sh 123.dkr.ecr.us-east-1.amazonaws.com/raiji-app homolog-previous homolog-current
# =============================================================================
set -euo pipefail

REPO="${1:?uso: ecr-retag.sh <repo> <tag-origem> <tag-destino>}"
SRC="${2:?tag de origem ausente}"
DST="${3:?tag de destino ausente}"
shift 3
REGION="${AWS_REGION_NAME:-${AWS_REGION:-us-east-1}}"
IF_MISSING="fail"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region) REGION="$2"; shift 2 ;;
    --if-missing=*) IF_MISSING="${1#*=}"; shift ;;
    *) echo "opção inválida: $1" >&2; exit 2 ;;
  esac
done

REPO_NAME="${REPO##*/}"

MANIFEST="$(aws ecr batch-get-image --region "$REGION" \
  --repository-name "$REPO_NAME" --image-ids imageTag="$SRC" \
  --query 'images[0].imageManifest' --output text 2>/dev/null || true)"

if [[ -z "$MANIFEST" || "$MANIFEST" == "None" ]]; then
  if [[ "$IF_MISSING" == "skip" ]]; then
    echo "[ecr-retag] tag '$SRC' não existe em $REPO_NAME — nada a fazer (primeiro deploy?)."
    exit 0
  fi
  echo "[ecr-retag] ERRO: tag '$SRC' não encontrada em $REPO_NAME." >&2
  exit 1
fi

if aws ecr put-image --region "$REGION" --repository-name "$REPO_NAME" \
     --image-tag "$DST" --image-manifest "$MANIFEST" >/dev/null 2>&1; then
  echo "[ecr-retag] $REPO_NAME:$SRC -> $REPO_NAME:$DST (retag OK)"
else
  # ImageAlreadyExistsException: a tag destino já aponta para a mesma imagem.
  echo "[ecr-retag] $REPO_NAME:$DST já aponta para esta imagem (ok)."
fi
