#!/usr/bin/env bash
# =============================================================================
# smoke-test.sh — S1-03 · Smoke tests pós-deploy
#
# Checagens (exatamente as da issue):
#   health -> GET  {BASE_URL}/actuator/health            == 200 e {"status":"UP"}
#   realm  -> GET  {KEYCLOAK_URL}/realms/raiji           == 200
#   token  -> POST {KEYCLOAK_URL}/realms/raiji/protocol/openid-connect/token == 200
#   me     -> GET  {BASE_URL}/api/me com Bearer token    == 200
#
# Uso:
#   ./scripts/smoke-test.sh                       # todas, em PARALELO (padrão)
#   ./scripts/smoke-test.sh --serial              # todas, em série
#   ./scripts/smoke-test.sh --check health        # uma checagem (usado na matriz do CD)
#
# Variáveis de ambiente:
#   BASE_URL (default http://localhost:8000)  KEYCLOAK_URL (default = BASE_URL)
#   REALM (raiji)  CLIENT_ID (raiji-api)  SMOKE_USERNAME (teste)  SMOKE_PASSWORD (teste123)
#   HTTP_TIMEOUT (10)  RETRIES (3)  RETRY_DELAY (5)  SMOKE_FORCE_PYTHON (0|1)
#
# HTTP: usa curl se existir; senão, python3 (urllib) como fallback.
# Saída: 0 = todas passaram | 1 = alguma falhou | 2 = erro de uso
# =============================================================================
set -u

CHECKS_ALL=(health realm token me)
CHECK=""
MODE="parallel"
BASE_URL="${BASE_URL:-http://localhost:8000}"
KEYCLOAK_URL="${KEYCLOAK_URL:-}"
REALM="${REALM:-raiji}"
CLIENT_ID="${CLIENT_ID:-raiji-api}"
SMOKE_USERNAME="${SMOKE_USERNAME:-teste}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-teste123}"
HTTP_TIMEOUT="${HTTP_TIMEOUT:-10}"
RETRIES="${RETRIES:-3}"
RETRY_DELAY="${RETRY_DELAY:-5}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)        CHECK="${2:-}"; shift 2 ;;
    --all)          CHECK=""; shift ;;
    --parallel)     MODE="parallel"; shift ;;
    --serial)       MODE="serial"; shift ;;
    --base)         BASE_URL="${2:-}"; shift 2 ;;
    --keycloak)     KEYCLOAK_URL="${2:-}"; shift 2 ;;
    --realm)        REALM="${2:-}"; shift 2 ;;
    --client-id)    CLIENT_ID="${2:-}"; shift 2 ;;
    --user)         SMOKE_USERNAME="${2:-}"; shift 2 ;;
    --password)     SMOKE_PASSWORD="${2:-}"; shift 2 ;;
    --retries)      RETRIES="${2:-}"; shift 2 ;;
    --retry-delay)  RETRY_DELAY="${2:-}"; shift 2 ;;
    --timeout)      HTTP_TIMEOUT="${2:-}"; shift 2 ;;
    -h|--help)      grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

# KEYCLOAK_URL padrão = BASE_URL (o Kong roteia /realms) — resolvido DEPOIS do
# parse para respeitar --base.
[[ -n "$KEYCLOAK_URL" ]] || KEYCLOAK_URL="$BASE_URL"

# Cores apenas em terminal (logs de CI saem limpos e "grepáveis").
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YLW=$'\033[1;33m'; NC=$'\033[0m'
else
  RED=""; GRN=""; YLW=""; NC=""
fi
ok()   { printf '%s[PASS]%s %s\n' "$GRN" "$NC" "$*"; }
fail() { printf '%s[FAIL]%s %s\n' "$RED" "$NC" "$*"; }
info() { printf '%s[..]%s %s\n'   "$YLW" "$NC" "$*"; }

TMPDIR_SMOKE="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_SMOKE"' EXIT

# -----------------------------------------------------------------------------
# Camada HTTP — curl preferencial, python3 como fallback.
# Preenche: HTTP_CODE (numérico; 0 = erro de conexão) e HTTP_BODY (arquivo).
# -----------------------------------------------------------------------------
_urlencode() {
  local s="$1" out="" i c
  for (( i = 0; i < ${#s}; i++ )); do
    c="${s:i:1}"
    case "$c" in
      [a-zA-Z0-9.~_-]) out+="$c" ;;
      *) out+="$(printf '%%%02X' "'$c")" ;;
    esac
  done
  printf '%s' "$out"
}

_http_python() { # method url outfile content-type body auth
  SMOKE_METHOD="$1" SMOKE_URL="$2" SMOKE_CT="${4:-}" SMOKE_BODY="${5:-}" \
  SMOKE_AUTH="${6:-}" SMOKE_TIMEOUT="$HTTP_TIMEOUT" \
  python3 - "$3" <<'PYEOF'
import os, sys, urllib.request, urllib.error
req = urllib.request.Request(os.environ["SMOKE_URL"], method=os.environ["SMOKE_METHOD"])
body = os.environ.get("SMOKE_BODY") or ""
data = body.encode() if body else None
if data is not None:
    req.add_header("Content-Type", os.environ.get("SMOKE_CT") or "application/x-www-form-urlencoded")
auth = os.environ.get("SMOKE_AUTH") or ""
if auth:
    req.add_header("Authorization", auth)
try:
    resp = urllib.request.urlopen(req, data=data, timeout=int(float(os.environ.get("SMOKE_TIMEOUT", "10"))))
    code, payload = resp.status, resp.read()
except urllib.error.HTTPError as e:
    code, payload = e.code, e.read() if e.fp else b""
except Exception as e:  # conexão recusada, timeout, DNS...
    code, payload = 0, str(e).encode()
with open(sys.argv[1], "wb") as f:
    f.write(payload)
print(code)
PYEOF
}

_http_curl() { # method url outfile content-type body auth
  local method="$1" url="$2" out="$3" ct="${4:-}" body="${5:-}" auth="${6:-}"
  local args=(-sS -o "$out" -w '%{http_code}' --max-time "$HTTP_TIMEOUT" -X "$method")
  [[ -n "$ct"   ]] && args+=(-H "Content-Type: $ct")
  [[ -n "$auth" ]] && args+=(-H "Authorization: $auth")
  [[ -n "$body" ]] && args+=(--data "$body")
  local code
  code="$(curl "${args[@]}" "$url" 2>/dev/null || true)"
  [[ "$code" == "000" || -z "$code" ]] && code=0
  printf '%s' "$code"
}

HTTP_CODE=0
HTTP_BODY=""
http_request() { # method url [content-type] [body] [auth]
  HTTP_BODY="$TMPDIR_SMOKE/body.$$.$RANDOM"
  if [[ "${SMOKE_FORCE_PYTHON:-0}" == "1" ]] || ! command -v curl >/dev/null 2>&1; then
    HTTP_CODE="$(_http_python "$1" "$2" "$HTTP_BODY" "${3:-}" "${4:-}" "${5:-}")"
  else
    HTTP_CODE="$(_http_curl "$1" "$2" "$HTTP_BODY" "${3:-}" "${4:-}" "${5:-}")"
  fi
  [[ -z "$HTTP_CODE" ]] && HTTP_CODE=0
}

# -----------------------------------------------------------------------------
# Checagens
# -----------------------------------------------------------------------------
obter_token() { # -> grava token em TOKEN; retorna 0/1
  TOKEN=""
  local body
  body="grant_type=password&client_id=$(_urlencode "$CLIENT_ID")&username=$(_urlencode "$SMOKE_USERNAME")&password=$(_urlencode "$SMOKE_PASSWORD")"
  http_request POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
    "application/x-www-form-urlencoded" "$body" ""
  [[ "$HTTP_CODE" == "200" ]] || return 1
  TOKEN="$(sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$HTTP_BODY" | head -n1)"
  [[ -n "$TOKEN" ]]
}

check_health() {
  http_request GET "$BASE_URL/actuator/health"
  if [[ "$HTTP_CODE" == "200" ]] && grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$HTTP_BODY"; then
    ok "health — GET /actuator/health -> 200 {\"status\":\"UP\"}"
    return 0
  fi
  fail "health — GET /actuator/health -> HTTP $HTTP_CODE (esperado 200 + status UP); body: $(head -c 200 "$HTTP_BODY" 2>/dev/null)"
  return 1
}

check_realm() {
  http_request GET "$KEYCLOAK_URL/realms/$REALM"
  if [[ "$HTTP_CODE" == "200" ]]; then
    ok "realm — GET /realms/$REALM -> 200"
    return 0
  fi
  fail "realm — GET /realms/$REALM -> HTTP $HTTP_CODE (esperado 200)"
  return 1
}

check_token() {
  if obter_token; then
    ok "token — POST /realms/$REALM/protocol/openid-connect/token ($SMOKE_USERNAME) -> 200 + access_token"
    return 0
  fi
  fail "token — POST /realms/$REALM/protocol/openid-connect/token -> HTTP $HTTP_CODE (esperado 200); body: $(head -c 200 "$HTTP_BODY" 2>/dev/null)"
  return 1
}

check_me() {
  if ! obter_token; then
    fail "me — não foi possível obter token (HTTP $HTTP_CODE)"
    return 1
  fi
  http_request GET "$BASE_URL/api/me" "" "" "Bearer $TOKEN"
  if [[ "$HTTP_CODE" == "200" ]]; then
    ok "me — GET /api/me com Bearer token -> 200"
    return 0
  fi
  fail "me — GET /api/me -> HTTP $HTTP_CODE (esperado 200); body: $(head -c 200 "$HTTP_BODY" 2>/dev/null)"
  return 1
}

run_with_retries() { # check_name
  local name="$1" attempt
  for (( attempt = 1; attempt <= RETRIES; attempt++ )); do
    if "check_$name"; then return 0; fi
    [[ "$attempt" -lt "$RETRIES" ]] && sleep "$RETRY_DELAY"
  done
  return 1
}

# -----------------------------------------------------------------------------
# Execução
# -----------------------------------------------------------------------------
run_one() { # name -> grava rc em arquivo (modo paralelo)
  local name="$1"
  if run_with_retries "$name"; then
    echo 0 > "$TMPDIR_SMOKE/$name.rc"
  else
    echo 1 > "$TMPDIR_SMOKE/$name.rc"
  fi
}

main() {
  local checks=()
  if [[ -n "$CHECK" ]]; then
    case "$CHECK" in
      health|realm|token|me) checks=("$CHECK") ;;
      all|"") checks=("${CHECKS_ALL[@]}") ;;
      *) echo "Check inválido: $CHECK (use health|realm|token|me|all)" >&2; exit 2 ;;
    esac
  else
    checks=("${CHECKS_ALL[@]}")
  fi

  info "Smoke tests em $BASE_URL (modo: $MODE, checagens: ${checks[*]})"

  if [[ "$MODE" == "parallel" && ${#checks[@]} -gt 1 ]]; then
    # Paralelo: cada checagem em subshell independente (o check 'me' obtém o
    # próprio token, logo não há dependência entre os jobs).
    local name pids=()
    for name in "${checks[@]}"; do
      run_one "$name" &
      pids+=($!)
    done
    for pid in "${pids[@]}"; do wait "$pid" || true; done
  else
    local name
    for name in "${checks[@]}"; do
      run_one "$name"
    done
  fi

  local failed=0 name rc
  echo "----------------------------------------"
  for name in "${checks[@]}"; do
    rc="$(cat "$TMPDIR_SMOKE/$name.rc" 2>/dev/null || echo 1)"
    [[ "$rc" != "0" ]] && failed=$((failed + 1))
  done

  if [[ "$failed" -eq 0 ]]; then
    ok "SMOKE OK — ${#checks[@]}/${#checks[@]} checagens passaram"
    exit 0
  fi
  fail "SMOKE FALHOU — $failed/${#checks[@]} checagens falharam"
  exit 1
}

main
