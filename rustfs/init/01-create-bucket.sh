#!/bin/sh
# =============================================================================
# Executado pelo container `rustfs-init` (amazon/aws-cli) a cada `up`.
# Idempotente: cria o bucket só se não existir e reaplica as regras.
#
#   - bucket privado (sem acesso anônimo — só com link pré-assinado)
#   - lifecycle: apaga conversations/* após 1 dia (o chat já corta em 24h)
#   - CORS: o app envia/baixa direto do navegador
#
# Variáveis: S3_ENDPOINT, S3_BUCKET, CORS_ORIGINS (separadas por vírgula)
# =============================================================================
set -eu

S3_ENDPOINT="${S3_ENDPOINT:-http://rustfs:9000}"
S3_BUCKET="${S3_BUCKET:?S3_BUCKET obrigatório}"
CORS_ORIGINS="${CORS_ORIGINS:-http://localhost,http://localhost:3000}"

s3api() { aws --endpoint-url "$S3_ENDPOINT" s3api "$@"; }

echo "[rustfs-init] aguardando $S3_ENDPOINT ..."
tries=0
until s3api list-buckets >/dev/null 2>&1; do
  tries=$((tries + 1))
  if [ "$tries" -ge 60 ]; then
    echo "[rustfs-init] ERRO: RustFS não respondeu" >&2
    exit 1
  fi
  sleep 2
done

if s3api head-bucket --bucket "$S3_BUCKET" >/dev/null 2>&1; then
  echo "[rustfs-init] bucket $S3_BUCKET já existe"
else
  s3api create-bucket --bucket "$S3_BUCKET" >/dev/null
  echo "[rustfs-init] bucket $S3_BUCKET criado"
fi

s3api put-bucket-lifecycle-configuration --bucket "$S3_BUCKET" --lifecycle-configuration '{
  "Rules": [{
    "ID": "chat-media-24h",
    "Status": "Enabled",
    "Filter": { "Prefix": "conversations/" },
    "Expiration": { "Days": 1 },
    "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
  }]
}'

ORIGINS_JSON="$(printf '%s' "$CORS_ORIGINS" | sed 's/[[:space:]]//g; s/,/","/g; s/^/"/; s/$/"/')"
s3api put-bucket-cors --bucket "$S3_BUCKET" --cors-configuration "{
  \"CORSRules\": [{
    \"AllowedOrigins\": [$ORIGINS_JSON],
    \"AllowedMethods\": [\"GET\", \"HEAD\", \"POST\", \"PUT\"],
    \"AllowedHeaders\": [\"*\"],
    \"ExposeHeaders\": [\"ETag\"],
    \"MaxAgeSeconds\": 3000
  }]
}"

echo "[rustfs-init] lifecycle (1 dia) e CORS ($CORS_ORIGINS) aplicados"
