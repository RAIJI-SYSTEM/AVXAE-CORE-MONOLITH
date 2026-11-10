#!/usr/bin/env bash
# =============================================================================
# keycloak-configure-auth.sh — aplica a configuração de LOGIN num Keycloak que
# já está rodando (o --import-realm só vale na primeira vez que o realm é criado).
#
#   - pt-BR, cadastro aberto (e-mail como usuário), SEM confirmação de e-mail
#   - "Esqueci a senha" desligado até existir SMTP (Resend)
#   - client raiji-api: PKCE S256, retorno do app Android, CORS do Capacitor
#   - Google (login social): só se GOOGLE_CLIENT_ID e GOOGLE_CLIENT_SECRET
#     estiverem definidos — o segredo nunca vai para o repositório
#
# Credenciais do Google: em keycloak/.env (ignorado pelo git), lido automaticamente:
#   GOOGLE_CLIENT_ID=xxx.apps.googleusercontent.com
#   GOOGLE_CLIENT_SECRET=GOCSPX-...
#
# Uso:
#   ./scripts/keycloak-configure-auth.sh
#   GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... ./scripts/keycloak-configure-auth.sh
#
# Variáveis: KEYCLOAK_URL (http://127.0.0.1:8180), KC_ADMIN (admin), KC_ADMIN_PASSWORD (admin),
#            REALM (raiji), CLIENT_ID (raiji-api)
# =============================================================================
set -euo pipefail

# Lê só as variáveis GOOGLE_* do keycloak/.env (sem executar o arquivo)
ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/keycloak/.env"
if [[ -f "$ENV_FILE" ]]; then
  while IFS='=' read -r key value || [[ -n "$key" ]]; do
    key="$(printf '%s' "$key" | tr -d '[:space:]')"
    [[ "$key" == GOOGLE_CLIENT_ID || "$key" == GOOGLE_CLIENT_SECRET ]] || continue
    value="$(printf '%s' "$value" | tr -d '\r' | sed -E 's/^[[:space:]]+|[[:space:]]+$//g; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/')"
    [[ -n "${!key:-}" ]] || printf -v "$key" '%s' "$value" # variável de ambiente tem prioridade
  done < "$ENV_FILE"
fi

KEYCLOAK_URL="${KEYCLOAK_URL:-http://127.0.0.1:8180}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM="${REALM:-raiji}"
CLIENT_ID="${CLIENT_ID:-raiji-api}"

log() { echo "[keycloak-auth] $*"; }
die() { echo "[keycloak-auth] ERRO: $*" >&2; exit 1; }

TOKEN="$(curl -fsS -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli \
  --data-urlencode "username=$KC_ADMIN" --data-urlencode "password=$KC_ADMIN_PASSWORD" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')" || die "não foi possível autenticar no Keycloak ($KEYCLOAK_URL)"
[[ -n "$TOKEN" ]] || die "token de admin vazio"

api() { # método caminho [json]
  local method="$1" path="$2" body="${3:-}"
  curl -fsS -o /dev/null -w '%{http_code}' -X "$method" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    ${body:+--data "$body"} "$KEYCLOAK_URL/admin/realms/$REALM$path"
}

# --- 1) Realm ------------------------------------------------------------------
api PUT "" '{
  "registrationAllowed": true,
  "registrationEmailAsUsername": true,
  "resetPasswordAllowed": false,
  "verifyEmail": false,
  "loginWithEmailAllowed": true,
  "internationalizationEnabled": true,
  "supportedLocales": ["pt-BR", "en"],
  "defaultLocale": "pt-BR"
}' >/dev/null
log "realm: pt-BR, cadastro sem confirmação de e-mail, recuperação de senha desligada"

# --- 2) Client do app ----------------------------------------------------------
CLIENT_UUID="$(curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" \
  | sed -n 's/^\[{"id":"\([^"]*\)".*/\1/p')"
[[ -n "$CLIENT_UUID" ]] || die "client $CLIENT_ID não encontrado"

api PUT "/clients/$CLIENT_UUID" '{
  "redirectUris": ["http://localhost:3000/*", "http://localhost:5173/*", "http://localhost:8080/*",
                   "http://localhost:8000/*", "com.raiji.app://auth/callback"],
  "webOrigins": ["+", "http://localhost"],
  "attributes": { "pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+" }
}' >/dev/null
log "client $CLIENT_ID: PKCE S256, retorno com.raiji.app://auth/callback, CORS http://localhost"

# --- 3) Google -------------------------------------------------------------------
if [[ -n "${GOOGLE_CLIENT_ID:-}" && -n "${GOOGLE_CLIENT_SECRET:-}" ]]; then
  IDP="{
    \"alias\": \"google\",
    \"displayName\": \"Google\",
    \"providerId\": \"google\",
    \"enabled\": true,
    \"trustEmail\": true,
    \"firstBrokerLoginFlowAlias\": \"first broker login\",
    \"config\": {
      \"clientId\": \"$GOOGLE_CLIENT_ID\",
      \"clientSecret\": \"$GOOGLE_CLIENT_SECRET\",
      \"defaultScope\": \"openid profile email\",
      \"syncMode\": \"IMPORT\"
    }
  }"
  code="$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$REALM/identity-provider/instances/google")"
  if [[ "$code" == "200" ]]; then
    api PUT "/identity-provider/instances/google" "$IDP" >/dev/null
    log "Google atualizado"
  else
    api POST "/identity-provider/instances" "$IDP" >/dev/null
    log "Google criado"
  fi
  PUBLIC_URL="$(curl -fsS "$KEYCLOAK_URL/realms/$REALM/.well-known/openid-configuration" \
    | sed -n 's/.*"issuer":"\([^"]*\)".*/\1/p')"
  log "No Google Cloud, a 'Authorized redirect URI' deve ser: $PUBLIC_URL/broker/google/endpoint"
else
  log "Google: pulado (defina GOOGLE_CLIENT_ID e GOOGLE_CLIENT_SECRET para ativar)"
fi
