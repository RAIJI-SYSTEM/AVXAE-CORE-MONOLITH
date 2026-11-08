#!/bin/sh
# =============================================================================
# render-config.sh — executado pelo container `kong-init` (alpine/busybox)
#
# 1. Espera o realm do Keycloak responder (rede interna do compose).
# 2. Lê a chave RSA pública do realm.
# 3. Gera /out/kong.yml a partir de /kong/kong.yml.tmpl.
#
# Variáveis:
#   PUBLIC_BASE_URL  URL pública do Kong (= KC_HOSTNAME do Keycloak). Obrigatória.
#   KC_INTERNAL_URL  URL interna do Keycloak   (default http://keycloak:8080)
#   KC_REALM         realm                     (default raiji)
#   TEMPLATE / OUT   caminhos de entrada/saída (default /kong/kong.yml.tmpl, /out/kong.yml)
# =============================================================================
set -eu

PUBLIC_BASE_URL="${PUBLIC_BASE_URL:?PUBLIC_BASE_URL obrigatório}"
KC_INTERNAL_URL="${KC_INTERNAL_URL:-http://keycloak:8080}"
KC_REALM="${KC_REALM:-raiji}"
TEMPLATE="${TEMPLATE:-/kong/kong.yml.tmpl}"
OUT="${OUT:-/out/kong.yml}"
MAX_TRIES="${MAX_TRIES:-60}"

ISSUER="${PUBLIC_BASE_URL%/}/realms/${KC_REALM}"
REALM_URL="${KC_INTERNAL_URL%/}/realms/${KC_REALM}"

echo "[kong-init] aguardando realm em ${REALM_URL} ..."
tries=0
JSON=""
while :; do
  JSON="$(wget -qO- "$REALM_URL" 2>/dev/null || true)"
  case "$JSON" in *'"public_key"'*) break ;; esac
  tries=$((tries + 1))
  if [ "$tries" -ge "$MAX_TRIES" ]; then
    echo "[kong-init] ERRO: Keycloak não respondeu após ${MAX_TRIES} tentativas" >&2
    exit 1
  fi
  sleep 5
done

KEY="$(printf '%s' "$JSON" | sed -n 's/.*"public_key":"\([^"]*\)".*/\1/p')"
if [ -z "$KEY" ]; then
  echo "[kong-init] ERRO: public_key não encontrada na resposta do realm" >&2
  exit 1
fi

# PEM: linhas de 64 caracteres, indentadas para o bloco YAML do template.
KEY_FILE="$(mktemp)"
printf '%s\n' "$KEY" | fold -w 64 | sed 's/^/          /' > "$KEY_FILE"

mkdir -p "$(dirname "$OUT")"
awk -v iss="$ISSUER" -v keyfile="$KEY_FILE" '
  { sub(/\r$/, "") }   # tolera template com CRLF (checkout no Windows)
  /^__KC_PUBLIC_KEY__$/ { while ((getline line < keyfile) > 0) print line; next }
  { gsub(/__KC_ISSUER__/, iss); print }
' "$TEMPLATE" > "$OUT.tmp"
mv "$OUT.tmp" "$OUT"
chmod 644 "$OUT"
rm -f "$KEY_FILE"

echo "[kong-init] kong.yml gerado (issuer=${ISSUER})"
