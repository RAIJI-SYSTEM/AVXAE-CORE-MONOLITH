#!/bin/sh
# =============================================================================
# Cria (ou atualiza) os buckets do Raiji no S3 da AWS. Idempotente: pode rodar de novo.
#
#   - bloqueio de acesso público ligado (só links assinados);
#   - mídia do chat: apaga conversations/* depois de 1 dia; CORS para o app;
#   - documentos (foto da CNH): apaga cnh/* depois de 1 dia (o backend já apaga na decisão).
#
# Lê do .env da raiz: AWS_S3_REGION, AWS_S3_ACCESS_KEY_ID, AWS_S3_SECRET_ACCESS_KEY,
# S3_MEDIA_BUCKET, S3_DOCS_BUCKET e (opcional) S3_CORS_ORIGINS.
# Roda o aws-cli num container: não precisa instalar nada.
#
# Uso (na pasta monolith):  ./scripts/s3-aws-setup.sh
# =============================================================================
set -eu
cd "$(dirname "$0")/.."

env_value() { grep -E "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2- | sed 's/^"//; s/"$//'; }

REGION="$(env_value AWS_S3_REGION)"
KEY_ID="$(env_value AWS_S3_ACCESS_KEY_ID)"
SECRET="$(env_value AWS_S3_SECRET_ACCESS_KEY)"
MEDIA="$(env_value S3_MEDIA_BUCKET)"
DOCS="$(env_value S3_DOCS_BUCKET)"
ORIGINS="$(env_value S3_CORS_ORIGINS)"
ORIGINS="${ORIGINS:-http://localhost,http://localhost:3000}"

for v in REGION KEY_ID SECRET MEDIA DOCS; do
  eval "val=\${$v}"
  if [ -z "$val" ]; then
    echo "Falta no .env: AWS_S3_REGION, AWS_S3_ACCESS_KEY_ID, AWS_S3_SECRET_ACCESS_KEY, S3_MEDIA_BUCKET e S3_DOCS_BUCKET" >&2
    exit 1
  fi
done

aws() {
  docker run --rm -e AWS_ACCESS_KEY_ID="$KEY_ID" -e AWS_SECRET_ACCESS_KEY="$SECRET" \
    -e AWS_DEFAULT_REGION="$REGION" amazon/aws-cli:2.38.5 "$@"
}

create_bucket() {
  if aws s3api head-bucket --bucket "$1" >/dev/null 2>&1; then
    echo "bucket $1 já existe"
  elif [ "$REGION" = "us-east-1" ]; then
    aws s3api create-bucket --bucket "$1" >/dev/null && echo "bucket $1 criado"
  else
    aws s3api create-bucket --bucket "$1" --create-bucket-configuration "LocationConstraint=$REGION" >/dev/null \
      && echo "bucket $1 criado"
  fi
  aws s3api put-public-access-block --bucket "$1" --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
}

expire_after_one_day() {
  aws s3api put-bucket-lifecycle-configuration --bucket "$1" --lifecycle-configuration "{
    \"Rules\": [{
      \"ID\": \"apagar-$2-1-dia\",
      \"Status\": \"Enabled\",
      \"Filter\": { \"Prefix\": \"$2/\" },
      \"Expiration\": { \"Days\": 1 },
      \"AbortIncompleteMultipartUpload\": { \"DaysAfterInitiation\": 1 }
    }]
  }"
}

create_bucket "$MEDIA"
expire_after_one_day "$MEDIA" conversations
ORIGINS_JSON="$(printf '%s' "$ORIGINS" | sed 's/[[:space:]]//g; s/,/","/g; s/^/"/; s/$/"/')"
aws s3api put-bucket-cors --bucket "$MEDIA" --cors-configuration "{
  \"CORSRules\": [{
    \"AllowedOrigins\": [$ORIGINS_JSON],
    \"AllowedMethods\": [\"GET\", \"HEAD\", \"POST\"],
    \"AllowedHeaders\": [\"*\"],
    \"ExposeHeaders\": [\"ETag\"],
    \"MaxAgeSeconds\": 3000
  }]
}"
echo "mídia: $MEDIA (24 h, CORS: $ORIGINS)"

create_bucket "$DOCS"
expire_after_one_day "$DOCS" cnh
echo "documentos: $DOCS (foto da CNH, no máximo 1 dia)"
echo "Pronto. Suba de novo: docker compose --profile chat up -d"
