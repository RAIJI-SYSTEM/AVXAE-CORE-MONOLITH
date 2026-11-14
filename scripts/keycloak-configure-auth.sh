#!/usr/bin/env bash
# =============================================================================
# keycloak-configure-auth.sh — aplica a configuração de LOGIN num Keycloak que
# já está rodando (o --import-realm só vale na primeira vez que o realm é criado).
#
#   - pt-BR, cadastro aberto (e-mail como usuário)
#   - toda conta é PASSAGEIRA (PASSENGER nas roles padrão); DRIVER é acumulável
#   - E-MAIL (Resend via SMTP): com RESEND_API_KEY, liga a CONFIRMAÇÃO de e-mail
#     no cadastro e o "ESQUECI A SENHA". Sem a chave, os dois ficam desligados
#     (ninguém fica preso esperando um e-mail que nunca chega).
#   - client raiji-api: PKCE S256, retorno do app Android, CORS do Capacitor
#   - Google (login social): só se GOOGLE_CLIENT_ID e GOOGLE_CLIENT_SECRET
#     estiverem definidos — o segredo nunca vai para o repositório
#
# Lidos automaticamente (arquivos fora do git; variável de ambiente tem prioridade):
#   keycloak/.env   GOOGLE_CLIENT_ID=xxx.apps.googleusercontent.com
#                   GOOGLE_CLIENT_SECRET=GOCSPX-...
#   .env (raiz)     RESEND_API_KEY=re_...
#                   RESEND_FROM=Raiji <nao-responda@seudominio.com.br>   (domínio verificado no Resend)
#
# Uso:
#   ./scripts/keycloak-configure-auth.sh
#   GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... ./scripts/keycloak-configure-auth.sh
#
# Variáveis: KEYCLOAK_URL (http://127.0.0.1:8180), KC_ADMIN (admin), KC_ADMIN_PASSWORD (admin),
#            REALM (raiji), CLIENT_ID (raiji-api)
# =============================================================================
set -euo pipefail

# Lê só variáveis permitidas dos .env (sem executar os arquivos)
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
load_env() { # arquivo nomes-permitidos...
  local file="$1"; shift
  [[ -f "$file" ]] || return 0
  while IFS='=' read -r key value || [[ -n "$key" ]]; do
    key="$(printf '%s' "$key" | tr -d '[:space:]')"
    local allowed=false
    for name in "$@"; do [[ "$key" == "$name" ]] && allowed=true; done
    $allowed || continue
    value="$(printf '%s' "$value" | tr -d '\r' | sed -E 's/^[[:space:]]+|[[:space:]]+$//g; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/')"
    [[ -n "${!key:-}" ]] || printf -v "$key" '%s' "$value" # variável de ambiente tem prioridade
  done < "$file"
}
load_env "$ROOT_DIR/keycloak/.env" GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET
load_env "$ROOT_DIR/.env" RESEND_API_KEY RESEND_FROM

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
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

if [[ -n "${RESEND_API_KEY:-}" ]]; then
  # "Raiji <nao-responda@dominio>" -> nome + e-mail (o Keycloak quer o e-mail puro em "from")
  FROM_RAW="${RESEND_FROM:-Raiji <onboarding@resend.dev>}"
  if [[ "$FROM_RAW" =~ ^(.*)\<([^>]+)\>[[:space:]]*$ ]]; then
    FROM_NAME="$(printf '%s' "${BASH_REMATCH[1]}" | sed -E 's/[[:space:]]+$//')"
    FROM_EMAIL="${BASH_REMATCH[2]}"
  else
    FROM_NAME="Raiji"; FROM_EMAIL="$FROM_RAW"
  fi
  [[ -n "$FROM_NAME" ]] || FROM_NAME="Raiji"

  # Resend via SMTP: smtp.resend.com:465 (SSL), usuário "resend", senha = chave da API
  api PUT "" "{
    \"registrationAllowed\": true,
    \"registrationEmailAsUsername\": true,
    \"resetPasswordAllowed\": true,
    \"verifyEmail\": true,
    \"loginWithEmailAllowed\": true,
    \"internationalizationEnabled\": true,
    \"supportedLocales\": [\"pt-BR\", \"en\"],
    \"defaultLocale\": \"pt-BR\",
    \"smtpServer\": {
      \"host\": \"smtp.resend.com\",
      \"port\": \"465\",
      \"ssl\": \"true\",
      \"starttls\": \"false\",
      \"auth\": \"true\",
      \"user\": \"resend\",
      \"password\": \"$(json_escape "$RESEND_API_KEY")\",
      \"from\": \"$(json_escape "$FROM_EMAIL")\",
      \"fromDisplayName\": \"$(json_escape "$FROM_NAME")\"
    }
  }" >/dev/null
  log "realm: pt-BR, CONFIRMAÇÃO de e-mail e ESQUECI A SENHA ligados (Resend, remetente $FROM_EMAIL)"
  [[ "$FROM_EMAIL" == *@resend.dev ]] && \
    log "AVISO: remetente de teste do Resend — só entrega para o e-mail do dono da conta Resend. Verifique seu domínio no Resend e defina RESEND_FROM."
else
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
  log "realm: pt-BR; SEM e-mail (defina RESEND_API_KEY): confirmação e recuperação de senha desligadas"
fi

# --- 1b) Toda conta é passageira (PASSENGER nas roles padrão) --------------------
# Vale também para as contas que já existem: elas têm default-roles-raiji, e a
# composição é avaliada a cada token. DRIVER continua sendo acrescentado à parte.
PASSENGER_ROLE="$(curl -fsS -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/$REALM/roles/PASSENGER")" \
  || die "role PASSENGER não encontrada no realm $REALM"
api POST "/roles/default-roles-$REALM/composites" "[$PASSENGER_ROLE]" >/dev/null
log "roles padrão: PASSENGER incluída (toda conta pode pedir vaga; motorista é acumulável)"

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
