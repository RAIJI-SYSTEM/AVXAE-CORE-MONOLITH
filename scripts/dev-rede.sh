#!/usr/bin/env bash
# Deixa o ambiente de desenvolvimento acessível pela REDE (Wi-Fi), para testar o app
# em vários celulares sem cabo. Ou volta para localhost (cabo USB + adb reverse).
#
#   ./scripts/dev-rede.sh            usa o IP atual do PC na rede
#   ./scripts/dev-rede.sh 10.0.0.17  usa este IP
#   ./scripts/dev-rede.sh localhost  volta para localhost
#
# O que muda:
#   monolith/.env        PUBLIC_BASE_URL (Keycloak, Kong e chat usam este endereço)
#   raiji-app/.env.local MOBILE_API_URL e NEXT_PUBLIC_KEYCLOAK_URL (endereços que o app usa)
# e reinicia Keycloak, Kong e chat para valerem. Depois:
#   - reinicie o "npm run dev:lan" do raiji-app (ele lê o .env.local ao iniciar)
#   - recompile o app (npm run build:mobile): os endereços ficam gravados no build
#   - entre de novo no app (os logins antigos eram de outro endereço)
#
# Limitação: o login com Google NÃO funciona por IP (o Google só aceita localhost ou HTTPS).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ENV="$ROOT/../raiji-app/.env.local"
HOST="${1:-$(hostname -I | awk '{print $1}')}"

if [ -z "$HOST" ]; then
  echo "Não achei o IP do PC. Informe: ./scripts/dev-rede.sh 192.168.0.10" >&2
  exit 1
fi

set_var() { # arquivo, nome, valor
  local file="$1" name="$2" value="$3"
  touch "$file"
  if grep -qE "^${name}=" "$file"; then
    sed -i -E "s#^${name}=.*#${name}=${value}#" "$file"
  else
    printf '\n%s=%s\n' "$name" "$value" >> "$file"
  fi
}

set_var "$ROOT/.env" PUBLIC_BASE_URL "http://${HOST}:8000"
set_var "$APP_ENV" MOBILE_API_URL "http://${HOST}:3000"
set_var "$APP_ENV" NEXT_PUBLIC_KEYCLOAK_URL "http://${HOST}:8000"
echo "[dev-rede] endereço: ${HOST} (Kong/Keycloak :8000, app/BFF :3000)"

cd "$ROOT"
# Keycloak (emissor dos tokens), Kong (confere o emissor) e chat (idem) precisam do mesmo endereço
docker compose --profile chat up -d --force-recreate keycloak kong-init kong chat >/dev/null
echo "[dev-rede] Keycloak, Kong e chat reiniciados. Aguardando o Kong..."
for _ in $(seq 1 60); do
  curl -fs "http://${HOST}:8000/realms/raiji" >/dev/null 2>&1 && break
  sleep 3
done
ISS=$(curl -fs "http://${HOST}:8000/realms/raiji/.well-known/openid-configuration" | sed -E 's/.*"issuer":"([^"]+)".*/\1/')
echo "[dev-rede] emissor dos tokens: ${ISS}"
echo "[dev-rede] próximos passos: reinicie o npm run dev:lan, rode npm run build:mobile e entre de novo no app."
